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
    private static class FakePcm implements WorldAudioPlayback.Pcm {
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

    /**
     * One accepted write and how much of it was heard. A line holds up to its buffer of written
     * frames unplayed; closing without {@code drain()} discards them, as a real line does.
     */
    private static final class Heard {
        /** Counts are in 16-bit samples. */
        final int line, frames;
        final byte[] bytes;
        volatile int discarded;

        Heard(int line, byte[] bytes) {
            this.line = line;
            this.bytes = bytes;
            frames = bytes.length / 2;
        }

        boolean heard() {
            return discarded < frames;
        }

        /** Magnitude of the last sample that was heard. */
        int lastSample() {
            int at = 2 * (frames - discarded - 1);
            return at < 0 ? 0 : Math.abs((short) (bytes[at] << 8 | bytes[at + 1] & 0xff));
        }
    }

    private static final class Device {
        final AtomicInteger opened = new AtomicInteger(), closedLines = new AtomicInteger();
        /** Every write on every line of this device, in order, with what was heard of it. */
        final java.util.List<Heard> log = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** The latest write's record. */
        volatile Heard latest;
        private final AtomicInteger lineIds = new AtomicInteger();
        final AtomicLong frames = new AtomicLong();
        final ConcurrentLinkedQueue<Integer> peaks = new ConcurrentLinkedQueue<>();
        /** Magnitude of the first sample of each accepted write, in order. */
        final ConcurrentLinkedQueue<Integer> firsts = new ConcurrentLinkedQueue<>();
        /** Magnitude of the last sample of the latest accepted write. */
        volatile int end;
        /** Thrown by drain, when set. */
        volatile RuntimeException drainFailure;
        /** Bytes per accepted write, in order. */
        final ConcurrentLinkedQueue<Integer> writes = new ConcurrentLinkedQueue<>();
        volatile int last;
        /** A removed device or a line stopped by the OS accepts no bytes. */
        volatile boolean dead;
        /** Writes accepted before the device stops accepting bytes. */
        volatile int dieAfter = Integer.MAX_VALUE;

        /** Frames heard on every line so far. */
        long heardFrames() {
            return log.stream().mapToLong(h -> h.frames - h.discarded).sum();
        }

        /** The last write that was at least partly heard, on any line. */
        Heard lastHeard() {
            var heard = log.stream().filter(Heard::heard).toList();
            return heard.isEmpty() ? null : heard.getLast();
        }

        SourceDataLine line() {
            int id = lineIds.getAndIncrement();
            // Written frames not yet played, oldest first; at most the line's buffer.
            var pending = new java.util.ArrayDeque<Heard>();
            int[] buffer = {Integer.MAX_VALUE}, pendingFrames = {0};
            return (SourceDataLine) Proxy.newProxyInstance(
                    WorldAudioPlaybackTest.class.getClassLoader(),
                    new Class<?>[] {SourceDataLine.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "write" -> {
                            if (dead || log.size() >= dieAfter) yield 0;
                            byte[] bytes = (byte[]) args[0];
                            int offset = (Integer) args[1], length = (Integer) args[2];
                            int peak = 0;
                            for (int i = offset; i + 1 < offset + length; i += 2)
                                peak = Math.max(peak, Math.abs((short) (bytes[i] << 8
                                        | bytes[i + 1] & 0xff)));
                            peaks.add(peak);
                            firsts.add(length < 2 ? 0 : Math.abs((short) (bytes[offset] << 8
                                    | bytes[offset + 1] & 0xff)));
                            end = length < 2 ? 0 : Math.abs((short) (bytes[offset + length - 2] << 8
                                    | bytes[offset + length - 1] & 0xff));
                            writes.add(length);
                            last = peak;
                            frames.addAndGet(length / 2);
                            var heard = new Heard(id, java.util.Arrays.copyOfRange(bytes, offset,
                                    offset + length));
                            synchronized (pending) {
                                pending.add(heard);
                                pendingFrames[0] += heard.frames;
                                // Frames older than one buffer have been played.
                                while (pendingFrames[0] - pending.peekFirst().frames
                                        >= buffer[0]) {
                                    pendingFrames[0] -= pending.pollFirst().frames;
                                }
                            }
                            log.add(heard);
                            latest = heard;
                            Thread.sleep(1);
                            yield length;
                        }
                        case "open" -> {
                            // The buffer in bytes, counted here in 16-bit samples.
                            if (args != null && args.length == 2) buffer[0] = (Integer) args[1] / 2;
                            yield null;
                        }
                        case "close" -> {
                            synchronized (pending) {
                                // Closing without a drain discards what the buffer holds.
                                int unplayed = Math.min(pendingFrames[0], buffer[0]);
                                for (var it = pending.descendingIterator();
                                        it.hasNext() && unplayed > 0; ) {
                                    var h = it.next();
                                    h.discarded = Math.min(h.frames, unplayed);
                                    unplayed -= h.discarded;
                                }
                                pending.clear();
                                pendingFrames[0] = 0;
                            }
                            closedLines.incrementAndGet();
                            yield null;
                        }
                        case "drain" -> {
                            if (drainFailure != null) throw drainFailure;
                            synchronized (pending) {
                                pending.clear();
                                pendingFrames[0] = 0;
                            }
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
        if (first > 6000 || device.last < 16000)
            throw new AssertionError("Music must fade in from silence: " + first + " " + device.last);
        if (statuses.stream().noneMatch(s -> s.playing() && s.message().contains("sound kit #100")))
            throw new AssertionError("Playing status must name the recorded sound kit");

        // An inherited parent-zone kit keeps playing across a subzone change.
        music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), FILES);
        Thread.sleep(100);
        if (streams.size() != 1) throw new AssertionError("Same kit restarted");

        // A different kit cross-fades: the old track keeps sounding while the next one starts,
        // then fades out over the recorded ramp and closes (gain curves are in crossfade tests).
        var replaced = streams.peek();
        long sentBefore = replaced.sent;
        music.track(track(WorldAreaAudio.Kind.MUSIC, 200, 0), FILES);
        await(() -> streams.size() == 2, "new kit did not start");
        await(() -> replaced.closed, "replaced decoder was not closed");
        await(() -> device.closedLines.get() >= 1, "replaced line was not closed");
        long faded = replaced.sent - sentBefore;
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
        // Wait on the recorded times: the counter is bumped before the time is queued.
        await(() -> openTimes.size() >= 3, "music did not repeat after silence");
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

        var opens = new AtomicInteger();
        var empty = new ConcurrentLinkedQueue<WorldAudioPlayback.Status>();
        var silent = new WorldAudioPlayback(format -> device.line(), (bytes, canceled) -> {
            opens.incrementAndGet();
            return new FakePcm(0);
        }, empty::add);
        silent.ambience.track(track(WorldAreaAudio.Kind.AMBIENCE, 8, 0), FILES);
        silent.ambience.enabled(true);
        await(() -> empty.stream().anyMatch(s -> s.message().contains("decoded no audio")),
                "empty decode was hidden");
        Thread.sleep(100);
        if (opens.get() != 1) throw new AssertionError("Empty stream respawned decoders");
        silent.closeAsync().get(10, TimeUnit.SECONDS);
    }

    private static long count(ConcurrentLinkedQueue<WorldAudioPlayback.Status> statuses,
            String message) {
        return statuses.stream().filter(s -> s.message().equals(message)).count();
    }

    private static void missingDeviceIsReportedBeforeAnyFileOrDecoder() throws Exception {
        var reads = new AtomicInteger();
        var decodes = new AtomicInteger();
        var lines = new AtomicInteger();
        var statuses = new ConcurrentLinkedQueue<WorldAudioPlayback.Status>();
        WorldAudioPlayback.Source counted = (id, token) -> {
            reads.incrementAndGet();
            return FILES.file(id, token);
        };
        var playback = new WorldAudioPlayback(() -> 0, format -> {
            lines.incrementAndGet();
            throw new javax.sound.sampled.LineUnavailableException("no mixer");
        }, (bytes, canceled) -> {
            decodes.incrementAndGet();
            return new FakePcm(400);
        }, statuses::add);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), counted);
        playback.music.enabled(true);
        await(() -> count(statuses, WorldAudioPlayback.OUTPUT_UNAVAILABLE) == 1,
                "missing device was hidden");
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 200, 0), counted);
        await(() -> count(statuses, WorldAudioPlayback.OUTPUT_UNAVAILABLE) == 2,
                "next kit did not report the missing device");
        Thread.sleep(100);
        if (reads.get() != 0 || decodes.get() != 0 || lines.get() != 0)
            throw new AssertionError("No device must read no file and start no decoder: "
                    + reads.get() + " " + decodes.get() + " " + lines.get());
        if (count(statuses, WorldAudioPlayback.OUTPUT_UNAVAILABLE) != 2)
            throw new AssertionError("Unavailable output must wait instead of retrying");
        playback.closeAsync().get(10, TimeUnit.SECONDS);

        // The probe can pass while opening the real format fails: report once, do not retry.
        var failing = new WorldAudioPlayback(() -> 2, format -> {
            lines.incrementAndGet();
            throw new javax.sound.sampled.LineUnavailableException("busy");
        }, (bytes, canceled) -> {
            decodes.incrementAndGet();
            return new FakePcm(400);
        }, statuses::add);
        failing.ambience.track(track(WorldAreaAudio.Kind.AMBIENCE, 7, 0), counted);
        failing.ambience.enabled(true);
        await(() -> count(statuses, WorldAudioPlayback.OUTPUT_UNAVAILABLE) == 3,
                "line open failure was hidden");
        Thread.sleep(100);
        if (decodes.get() != 1 || lines.get() != 1)
            throw new AssertionError("Failed line open must not retry: " + decodes.get());
        failing.closeAsync().get(10, TimeUnit.SECONDS);
    }

    private static void deadLineAndStreamFailuresStopAmbienceLooping() throws Exception {
        var device = new Device();
        device.dead = true;
        var opens = new AtomicInteger();
        var statuses = new ConcurrentLinkedQueue<WorldAudioPlayback.Status>();
        var playback = new WorldAudioPlayback(format -> {
            device.opened.incrementAndGet();
            return device.line();
        }, (bytes, canceled) -> {
            opens.incrementAndGet();
            return new FakePcm(400);
        }, statuses::add);
        playback.ambience.track(track(WorldAreaAudio.Kind.AMBIENCE, 100, 0), FILES);
        playback.ambience.enabled(true);
        await(() -> count(statuses, WorldAudioPlayback.OUTPUT_STOPPED) == 1,
                "dead line was hidden");
        Thread.sleep(200);
        if (opens.get() != 1 || device.opened.get() != 1)
            throw new AssertionError("Dead line must not re-decode in a loop: " + opens.get());
        playback.closeAsync().get(10, TimeUnit.SECONDS);

        // A line whose write throws (device removed mid-write) is reported, not a dead worker.
        var throwingOpens = new AtomicInteger();
        var throwing = new WorldAudioPlayback(format -> (SourceDataLine) Proxy.newProxyInstance(
                WorldAudioPlaybackTest.class.getClassLoader(),
                new Class<?>[] {SourceDataLine.class}, (proxy, method, args) -> {
                    if (method.getName().equals("write"))
                        throw new IllegalStateException("device removed");
                    return method.getName().startsWith("is") ? false : null;
                }), (bytes, canceled) -> {
                    throwingOpens.incrementAndGet();
                    return new FakePcm(400);
                }, statuses::add);
        throwing.ambience.track(track(WorldAreaAudio.Kind.AMBIENCE, 11, 0), FILES);
        throwing.ambience.enabled(true);
        await(() -> count(statuses, WorldAudioPlayback.OUTPUT_STOPPED) == 2,
                "throwing line was hidden");
        Thread.sleep(200);
        if (throwingOpens.get() != 1)
            throw new AssertionError("Throwing line must not re-decode: " + throwingOpens.get());
        throwing.closeAsync().get(10, TimeUnit.SECONDS);

        var failing = new AtomicInteger();
        var broken = new WorldAudioPlayback(format -> new Device().line(), (bytes, canceled) -> {
            failing.incrementAndGet();
            return new FakePcm(400) {
                @Override
                public int read(byte[] buffer, int offset, int length, BooleanSupplier c) {
                    throw new IllegalStateException("unexpected");
                }
            };
        }, statuses::add);
        broken.ambience.track(track(WorldAreaAudio.Kind.AMBIENCE, 9, 0), FILES);
        broken.ambience.enabled(true);
        await(() -> count(statuses, "Recorded ambience stream failed.") == 1,
                "unexpected read failure was hidden");
        Thread.sleep(200);
        if (failing.get() != 1)
            throw new AssertionError("Read failure must not re-decode in a loop: " + failing.get());
        broken.closeAsync().get(10, TimeUnit.SECONDS);
    }

    private static void statusTextNamesNoLocalPaths() throws Exception {
        String home = "/home/someone/.local/jdk";
        var statuses = new ConcurrentLinkedQueue<WorldAudioPlayback.Status>();
        var start = new WorldAudioPlayback(format -> new Device().line(), (bytes, canceled) -> {
            throw new IOException("Cannot run program \"" + home + "/bin/java\": error=2");
        }, statuses::add);
        start.music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), FILES);
        start.music.enabled(true);
        await(() -> statuses.stream().anyMatch(s -> s.message().contains("could not start")),
                "decoder start failure was hidden");
        start.closeAsync().get(10, TimeUnit.SECONDS);

        var pipe = new WorldAudioPlayback(format -> new Device().line(), (bytes, canceled) ->
                new FakePcm(400) {
                    @Override
                    public int read(byte[] buffer, int offset, int length, BooleanSupplier c)
                            throws IOException {
                        throw new IOException(home + "/pipe: Broken pipe");
                    }
                }, statuses::add);
        pipe.music.track(track(WorldAreaAudio.Kind.MUSIC, 200, 0), FILES);
        pipe.music.enabled(true);
        await(() -> statuses.stream().anyMatch(s -> s.message().contains("output was lost")),
                "stream failure was hidden");
        pipe.closeAsync().get(10, TimeUnit.SECONDS);
        if (statuses.stream().anyMatch(s -> s.message().contains("/home")
                || s.message().contains("Cannot run")))
            throw new AssertionError("Status text must not name local paths: " + statuses);

        if (!WorldAudioPlayback.detail(new IOException(
                "Voice preview requires a complete valid single Vorbis stream"), "x")
                .equals("not a valid Vorbis stream"))
            throw new AssertionError("Shared Ogg preflight wording must fit world audio");

        // Authored decoder messages stay visible.
        if (!WorldAudioPlayback.detail(new IOException("World audio decoder stalled"), "x")
                .equals("World audio decoder stalled"))
            throw new AssertionError("Authored decoder detail was hidden");
    }

    /**
     * The fade position moves at a fixed rate and maps to amplitude on an equal-power curve; the
     * level is a separate linear scale.
     */
    private static void gainRampsPerFrame() {
        byte[] block = new byte[8];
        for (int i = 0; i < 8; i += 2) block[i] = 0x40;
        var gain = new WorldAudioPlayback.Envelope(0, 1);
        WorldAudioPlayback.apply(block, 8, 1, gain, 1, 1, 0.5, 1);
        if (gain.position != 1) throw new AssertionError("Ramp did not reach the end");
        int first = (short) (block[0] << 8 | block[1] & 0xff);
        int last = (short) (block[6] << 8 | block[7] & 0xff);
        if (first != (int) Math.round(0x4000 * Math.sin(Math.PI / 4)) || last != 0x4000)
            throw new AssertionError("Equal-power ramp " + first + " " + last);
        // Two voices crossing at the same rate keep their summed power at every step.
        for (double t = 0; t <= 1; t += 0.125) {
            double in = Math.sin(t * Math.PI / 2), out = Math.sin((1 - t) * Math.PI / 2);
            if (Math.abs(in * in + out * out - 1) > 1e-9)
                throw new AssertionError("Power dips at " + t);
        }
        // A fade lasts the same number of frames at any level.
        for (double level : new double[] {1, 0.25}) {
            var fading = new WorldAudioPlayback.Envelope(0, level);
            int frames = 0;
            while (fading.position < 1) {
                WorldAudioPlayback.apply(new byte[2], 2, 1, fading, 1, level, 0.01, 0.01);
                frames++;
            }
            if (frames != 100) throw new AssertionError("Fade at level " + level + ": " + frames);
        }
    }

    /**
     * The device probe must match how real mixers advertise formats. Windows DirectSound lists
     * concrete channel counts and frame sizes with only the rate unspecified; Linux mixers list
     * unspecified channels. {@code AudioFormat.matches} wildcards only the advertised side, so a
     * probe with unspecified channels or frame size matches nothing on Windows. A mono-only device
     * matches only a mono probe.
     */
    private static void probeMatchesWindowsLinuxAndMonoMixerFormats() {
        var signed = javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED;
        float any = javax.sound.sampled.AudioSystem.NOT_SPECIFIED;
        int unset = javax.sound.sampled.AudioSystem.NOT_SPECIFIED;
        var directSound = new java.util.ArrayList<javax.sound.sampled.AudioFormat>();
        var monoOnly = new java.util.ArrayList<javax.sound.sampled.AudioFormat>();
        for (int channels : new int[] {1, 2})
            for (boolean bigEndian : new boolean[] {false, true}) {
                var format = new javax.sound.sampled.AudioFormat(signed, any, 16, channels,
                        2 * channels, any, bigEndian);
                directSound.add(format);
                if (channels == 1) monoOnly.add(format);
            }
        var windows = mixer(directSound);
        if (AudioOutput.channels(windows::isFormatSupported) != 2)
            throw new AssertionError("Probe must match DirectSound's concrete mixer formats");
        var linux = mixer(List.of(new javax.sound.sampled.AudioFormat(
                signed, any, 16, unset, unset, any, true)));
        if (AudioOutput.channels(linux::isFormatSupported) != 2)
            throw new AssertionError("Probe must match mixers that leave channels unspecified");
        var mono = mixer(monoOnly);
        if (mono.isFormatSupported(new javax.sound.sampled.AudioFormat(signed, any, 16, 2, 4, any,
                true)))
            throw new AssertionError("A mono-only mixer must reject a stereo format");
        if (AudioOutput.channels(mono::isFormatSupported) != 1)
            throw new AssertionError("Probe must match a mono-only mixer");
        var eightBit = mixer(List.of(new javax.sound.sampled.AudioFormat(
                signed, any, 8, unset, unset, any, true)));
        if (AudioOutput.channels(eightBit::isFormatSupported) != 0)
            throw new AssertionError("Probe must still require 16-bit output");
    }

    private static javax.sound.sampled.DataLine.Info mixer(
            List<javax.sound.sampled.AudioFormat> formats) {
        int unset = javax.sound.sampled.AudioSystem.NOT_SPECIFIED;
        return new javax.sound.sampled.DataLine.Info(SourceDataLine.class,
                formats.toArray(javax.sound.sampled.AudioFormat[]::new), unset, unset);
    }

    private static short sample(byte[] block, int index) {
        return (short) (block[2 * index] << 8 | block[2 * index + 1] & 0xff);
    }

    /** (L + R) / 2 per frame, truncated toward zero, never overflowing 16 bits. */
    private static void downmixAveragesStereoFrames() {
        short[][] frames = {{1000, 3000}, {100, 51}, {-100, -51}, {Short.MAX_VALUE, Short.MAX_VALUE},
                {Short.MIN_VALUE, Short.MIN_VALUE}, {Short.MAX_VALUE, Short.MIN_VALUE}, {-1, 0}};
        int[] expected = {2000, 75, -75, Short.MAX_VALUE, Short.MIN_VALUE, 0, 0};
        byte[] block = new byte[frames.length * 4 + 3]; // a trailing partial frame is dropped
        for (int i = 0; i < frames.length; i++)
            for (int c = 0; c < 2; c++) {
                block[4 * i + 2 * c] = (byte) (frames[i][c] >> 8);
                block[4 * i + 2 * c + 1] = (byte) frames[i][c];
            }
        int count = AudioOutput.downmix(block, block.length);
        if (count != frames.length * 2) throw new AssertionError("Mono byte count " + count);
        for (int i = 0; i < expected.length; i++)
            if (sample(block, i) != expected[i])
                throw new AssertionError("Downmix frame " + i + ": " + sample(block, i));
    }

    /** Authored stereo 8 kHz stream: left 0x4000, right 0x2000 in every frame. */
    private static final class StereoPcm extends FakePcm {
        StereoPcm(long frames) { super(frames); }

        @Override public int channels() { return 2; }

        @Override
        public int read(byte[] buffer, int offset, int length, BooleanSupplier canceled) {
            if (sent >= frames) return -1;
            int count = (int) Math.min(length / 4, frames - sent);
            for (int i = 0; i < count; i++) {
                int at = offset + 4 * i;
                buffer[at] = 0x40;
                buffer[at + 1] = 0;
                buffer[at + 2] = 0x20;
                buffer[at + 3] = 0;
            }
            sent += count;
            return count * 4;
        }
    }

    /**
     * Plays one authored stereo track to its end on a device with the given mixer formats, and
     * checks the opened line's channels, every written sample and the full-volume peak.
     */
    private static void playsStereoTrackToTheEnd(javax.sound.sampled.DataLine.Info mixer,
            int lineChannels, int peak) throws Exception {
        long frames = 8000 * 2; // longer than the fade-in, so the end is at full volume
        var device = new Device();
        var formats = new ConcurrentLinkedQueue<javax.sound.sampled.AudioFormat>();
        var statuses = new ConcurrentLinkedQueue<WorldAudioPlayback.Status>();
        var playback = new WorldAudioPlayback(
                () -> AudioOutput.channels(mixer::isFormatSupported), format -> {
                    if (!mixer.isFormatSupported(format))
                        throw new javax.sound.sampled.LineUnavailableException("unsupported");
                    formats.add(format);
                    return device.line();
                }, (bytes, canceled) -> new StereoPcm(frames), statuses::add);
        playback.music.volume(1);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 100, MAX_SILENCE), FILES);
        playback.music.enabled(true);
        await(() -> statuses.stream().anyMatch(s -> s.message().startsWith("Recorded silence")),
                "stereo track did not play to its end");
        if (formats.size() != 1 || formats.peek().getChannels() != lineChannels)
            throw new AssertionError("Line channels " + formats);
        if (device.frames.get() != frames * lineChannels)
            throw new AssertionError("Samples written " + device.frames.get());
        if (device.last != peak) throw new AssertionError("Full-volume peak " + device.last);
        playback.closeAsync().get(10, TimeUnit.SECONDS);
    }

    private static final int MAX_SILENCE = WorldAudioPlayback.MAX_SILENCE_MILLIS;

    /**
     * A mono-only device plays a stereo track through a mono line, downmixed and faded in; a
     * stereo device keeps the stereo line and both channels.
     */
    private static void monoOnlyDevicesPlayDownmixedStereoAndStereoDevicesAreUnchanged()
            throws Exception {
        playsStereoTrackToTheEnd(devices(1), 1, 0x3000);
        playsStereoTrackToTheEnd(devices(1, 2), 2, 0x4000);
    }

    /** A DirectSound-style mixer listing 16-bit formats with the given channel counts. */
    private static javax.sound.sampled.DataLine.Info devices(int... channelCounts) {
        var signed = javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED;
        float any = javax.sound.sampled.AudioSystem.NOT_SPECIFIED;
        var formats = new java.util.ArrayList<javax.sound.sampled.AudioFormat>();
        for (int channels : channelCounts)
            for (boolean bigEndian : new boolean[] {false, true})
                formats.add(new javax.sound.sampled.AudioFormat(signed, any, 16, channels,
                        2 * channels, any, bigEndian));
        return mixer(formats);
    }

    /**
     * Ambience loops replay a cached copy of the decoded stream. On a mono-only device the cache
     * must hold the stereo stream, not the downmixed block, or every replay is downmixed twice
     * and plays half its samples. One pass (1000 frames) fits in one block, so each write is one
     * pass; enough passes run to finish the fade-in.
     */
    private static void monoOnlyAmbienceLoopsReplayWholePasses() throws Exception {
        long frames = 1000;
        var mono = devices(1);
        var device = new Device();
        var decodes = new AtomicInteger();
        var playback = new WorldAudioPlayback(
                () -> AudioOutput.channels(mono::isFormatSupported), format -> {
                    if (!mono.isFormatSupported(format))
                        throw new javax.sound.sampled.LineUnavailableException("unsupported");
                    return device.line();
                }, (bytes, canceled) -> {
                    decodes.incrementAndGet();
                    return new StereoPcm(frames);
                }, status -> {});
        playback.ambience.volume(1);
        playback.ambience.track(track(WorldAreaAudio.Kind.AMBIENCE, 100, 0), FILES);
        playback.ambience.enabled(true);
        int passes = (int) (8000 * WorldAudioPlayback.FADE_SECONDS / frames) + 4;
        await(() -> device.writes.size() >= passes, "ambience did not loop");
        playback.closeAsync().get(10, TimeUnit.SECONDS);
        if (decodes.get() != 1) throw new AssertionError("Ambience loop re-decoded its file");
        var writes = device.writes.toArray(Integer[]::new);
        for (int pass = 0; pass < passes; pass++)
            if (writes[pass] != frames * 2)
                throw new AssertionError("Pass " + pass + " wrote " + writes[pass] + " bytes");
        var peaks = device.peaks.toArray(Integer[]::new);
        if (peaks[passes - 1] != 0x3000)
            throw new AssertionError("Looped full-volume peak " + peaks[passes - 1]);
    }


    /** Authored mono stream at a given rate. */
    private static final class RatePcm extends FakePcm {
        final int rate;

        RatePcm(int rate, long frames) {
            super(frames);
            this.rate = rate;
        }

        @Override public int sampleRate() { return rate; }
    }

    private static WorldAreaAudio.Track weighted(WorldAreaAudio.Kind kind, long kit,
            List<Long> files, List<Integer> weights, double volume, int silence) {
        return new WorldAreaAudio.Track(kind, 10, "Authored zone", 5, kit, files, weights, volume,
                silence, silence);
    }

    /**
     * Files are picked by recorded weight (a zero weight never plays) and the recorded kit
     * volume scales the full-volume amplitude: 0.25 of 0x4000 is 0x1000.
     */
    private static void recordedWeightsPickFilesAndKitVolumeScalesGain() throws Exception {
        var device = new Device();
        var requested = new ConcurrentLinkedQueue<Long>();
        var statuses = new ConcurrentLinkedQueue<WorldAudioPlayback.Status>();
        WorldAudioPlayback.Source counted = (id, token) -> {
            requested.add(id);
            return FILES.file(id, token);
        };
        var playback = new WorldAudioPlayback(format -> device.line(),
                (bytes, canceled) -> new FakePcm(8000 * 2), statuses::add);
        playback.music.volume(1);
        playback.music.track(weighted(WorldAreaAudio.Kind.MUSIC, 100, List.of(1L, 2L, 3L),
                List.of(0, 4, 0), 0.25, 0), counted);
        playback.music.enabled(true);
        await(() -> requested.size() >= 3, "music did not repeat");
        await(() -> statuses.stream().filter(WorldAudioPlayback.Status::playing).count() >= 2,
                "music did not play twice");
        playback.closeAsync().get(10, TimeUnit.SECONDS);
        if (!requested.stream().allMatch(id -> id == 2L))
            throw new AssertionError("Only the weighted file may play: " + requested);
        int max = device.peaks.stream().mapToInt(Integer::intValue).max().orElse(0);
        if (max != 0x1000) throw new AssertionError("Kit volume peak " + max);
    }

    /**
     * An ambience kit of several files reopens a file per loop. Each reopen reports "Loading"
     * and must report "Playing" again, or the status stays on "Loading" while audio plays.
     */
    private static void ambienceReopenReportsPlayingAgain() throws Exception {
        var opens = new AtomicInteger();
        var statuses = new ConcurrentLinkedQueue<WorldAudioPlayback.Status>();
        var playback = new WorldAudioPlayback(format -> new Device().line(), (bytes, canceled) -> {
            opens.incrementAndGet();
            return new FakePcm(400);
        }, statuses::add);
        playback.ambience.track(weighted(WorldAreaAudio.Kind.AMBIENCE, 100, List.of(1L, 2L),
                List.of(1, 1), 1, 0), FILES);
        playback.ambience.enabled(true);
        await(() -> opens.get() >= 4, "ambience did not reopen its files");
        playback.ambience.enabled(false);
        playback.closeAsync().get(10, TimeUnit.SECONDS);
        var seen = statuses.toArray(WorldAudioPlayback.Status[]::new);
        int loadings = 0;
        for (int i = 0; i < seen.length; i++) {
            if (!seen[i].message().startsWith("Loading")) continue;
            loadings++;
            boolean last = java.util.Arrays.stream(seen, i + 1, seen.length)
                    .noneMatch(s -> s.message().startsWith("Loading"));
            if (last) continue; // the final load may be the one disabled
            if (i + 1 >= seen.length || !seen[i + 1].playing())
                throw new AssertionError("Loading was not followed by playing: "
                        + java.util.Arrays.toString(seen));
        }
        if (loadings < 4) throw new AssertionError("Expected reopen loads: " + loadings);
    }

    /**
     * A mixed-format ambience kit switches lines on a format change instead of ending the track
     * and fading in from silence over the 1.5 s fade on every loop. The new line ramps up from
     * silence within a few milliseconds, so it starts without a click and reaches full volume
     * inside its first block.
     */
    private static void mixedFormatAmbienceSwitchesLinesWithoutRestarting() throws Exception {
        var devices = new ConcurrentLinkedQueue<Device>();
        var formats = new ConcurrentLinkedQueue<javax.sound.sampled.AudioFormat>();
        var decodes = new AtomicInteger();
        var statuses = new ConcurrentLinkedQueue<WorldAudioPlayback.Status>();
        var playback = new WorldAudioPlayback(format -> {
            var device = new Device();
            devices.add(device);
            formats.add(format);
            return device.line();
        }, (bytes, canceled) -> {
            // The first file outlasts the fade-in; later files alternate rates.
            int n = decodes.incrementAndGet();
            return n == 1 ? new RatePcm(8000, 8000 * 2)
                    : new RatePcm(n % 2 == 0 ? 16000 : 8000, 2000);
        }, statuses::add);
        playback.ambience.volume(1);
        playback.ambience.track(weighted(WorldAreaAudio.Kind.AMBIENCE, 100, List.of(1L, 2L),
                List.of(1, 1), 1, 0), FILES);
        playback.ambience.enabled(true);
        await(() -> decodes.get() >= 4 && devices.size() >= 3, "mixed formats did not loop");
        playback.closeAsync().get(10, TimeUnit.SECONDS);
        var lines = devices.toArray(Device[]::new);
        var opened = formats.toArray(javax.sound.sampled.AudioFormat[]::new);
        if (opened[0].getSampleRate() != 8000 || opened[1].getSampleRate() != 16000)
            throw new AssertionError("Lines must follow the decoded formats");
        for (int i = 1; i < lines.length; i++) {
            Integer peak = lines[i].peaks.peek(), first = lines[i].firsts.peek();
            if (peak == null) continue; // the last line may be closed before it wrote
            if (first > 1000)
                throw new AssertionError("Line " + i + " started at full gain: " + first);
            if (peak < 16000)
                throw new AssertionError("Line " + i + " restarted the slow fade-in: " + peak);
        }
        if (lines[0].closedLines.get() != 1)
            throw new AssertionError("The replaced line was not closed");
        if (statuses.stream().anyMatch(s -> !s.playing() && !s.message().startsWith("Loading")))
            throw new AssertionError("A format change must not end the track: " + statuses);
    }

    /** A full source queue is retried with backoff instead of showing the file unavailable. */
    private static void busySourceIsRetriedNotUnavailable() throws Exception {
        var attempts = new AtomicInteger();
        var statuses = new ConcurrentLinkedQueue<WorldAudioPlayback.Status>();
        WorldAudioPlayback.Source busy = (id, token) -> attempts.incrementAndGet() <= 3
                ? CompletableFuture.failedFuture(new WorldAreaAudio.QueueFull(null))
                : FILES.file(id, token);
        var playback = new WorldAudioPlayback(format -> new Device().line(),
                (bytes, canceled) -> new FakePcm(Long.MAX_VALUE), statuses::add);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), busy);
        playback.music.enabled(true);
        await(() -> statuses.stream().anyMatch(WorldAudioPlayback.Status::playing),
                "busy source was not retried");
        if (attempts.get() != 4) throw new AssertionError("Attempts " + attempts.get());
        if (statuses.stream().noneMatch(s -> s.message().startsWith("Waiting for the audio source")
                && s.message().contains("file #1000")))
            throw new AssertionError("Repeated busy attempts must say so: " + statuses);
        if (statuses.stream().anyMatch(s -> s.message().contains("unavailable")))
            throw new AssertionError("A full queue was reported unavailable: " + statuses);
        // A disabled channel stops retrying a source that stays busy.
        playback.closeAsync().get(10, TimeUnit.SECONDS);
        var stuck = new AtomicInteger();
        var waiting = new WorldAudioPlayback(format -> new Device().line(),
                (bytes, canceled) -> new FakePcm(400), statuses::add);
        waiting.music.track(track(WorldAreaAudio.Kind.MUSIC, 200, 0), (id, token) -> {
            stuck.incrementAndGet();
            return CompletableFuture.failedFuture(new WorldAreaAudio.QueueFull(null));
        });
        waiting.music.enabled(true);
        await(() -> stuck.get() >= 2, "busy source was not retried");
        waiting.music.enabled(false);
        Thread.sleep(50);
        int after = stuck.get();
        Thread.sleep(WorldAudioPlayback.BUSY_RETRY_MAX_MILLIS + 200);
        if (stuck.get() != after) throw new AssertionError("Disabled channel kept retrying");
        waiting.closeAsync().get(10, TimeUnit.SECONDS);
    }

    /**
     * The recorded entry volume multiplies the kit volume per file (0.5 x 0.5 of 0x4000 is
     * 0x1000), and a file whose recorded volume is 0 says it is silent.
     */
    private static void entryVolumesScaleEachFileAndSilentKitsSaySo() throws Exception {
        var device = new Device();
        var statuses = new ConcurrentLinkedQueue<WorldAudioPlayback.Status>();
        var playback = new WorldAudioPlayback(format -> device.line(),
                (bytes, canceled) -> new FakePcm(8000 * 2), statuses::add);
        playback.music.volume(1);
        playback.music.track(new WorldAreaAudio.Track(WorldAreaAudio.Kind.MUSIC, 10,
                "Authored zone", 5, 100, List.of(1L), List.of(1), List.of(0.5), 0.5,
                MAX_SILENCE, MAX_SILENCE), FILES);
        playback.music.enabled(true);
        await(() -> statuses.stream().anyMatch(s -> s.message().startsWith("Recorded silence")),
                "music did not play to its end");
        playback.closeAsync().get(10, TimeUnit.SECONDS);
        int max = device.peaks.stream().mapToInt(Integer::intValue).max().orElse(0);
        if (max != 0x1000) throw new AssertionError("Entry volume peak " + max);
        if (statuses.stream().anyMatch(s -> s.message().contains("silent")))
            throw new AssertionError("An audible kit was called silent");

        var muted = new ConcurrentLinkedQueue<WorldAudioPlayback.Status>();
        var silent = new WorldAudioPlayback(format -> new Device().line(),
                (bytes, canceled) -> new FakePcm(Long.MAX_VALUE), muted::add);
        silent.music.track(weighted(WorldAreaAudio.Kind.MUSIC, 100, List.of(1L), List.of(1), 0,
                0), FILES);
        silent.music.enabled(true);
        await(() -> muted.stream().anyMatch(s -> s.playing()
                && s.message().contains("recorded volume is 0")), "silent kit was not reported");
        silent.closeAsync().get(10, TimeUnit.SECONDS);
    }

    /**
     * A drain or line-open failure at a mixed-format switch is reported once; the next decoder
     * is closed and nothing is re-decoded in a loop.
     */
    private static void failuresDuringAFormatSwitchStopCleanly() throws Exception {
        for (boolean drainFails : new boolean[] {true, false}) {
            var opens = new AtomicInteger();
            var decoded = new ConcurrentLinkedQueue<FakePcm>();
            var statuses = new ConcurrentLinkedQueue<WorldAudioPlayback.Status>();
            var playback = new WorldAudioPlayback(format -> {
                if (opens.incrementAndGet() > 1 && !drainFails)
                    throw new javax.sound.sampled.LineUnavailableException("format refused");
                var device = new Device();
                if (drainFails) device.drainFailure = new IllegalStateException("removed");
                return device.line();
            }, (bytes, canceled) -> {
                var pcm = new RatePcm(decoded.isEmpty() ? 8000 : 16000, 400);
                decoded.add(pcm);
                return pcm;
            }, statuses::add);
            playback.ambience.track(weighted(WorldAreaAudio.Kind.AMBIENCE, 100,
                    List.of(1L, 2L), List.of(1, 1), 1, 0), FILES);
            playback.ambience.enabled(true);
            String expected = drainFails ? WorldAudioPlayback.OUTPUT_STOPPED
                    : WorldAudioPlayback.OUTPUT_UNAVAILABLE;
            await(() -> count(statuses, expected) == 1, "switch failure was hidden: " + expected);
            Thread.sleep(200);
            if (decoded.size() != 2 || count(statuses, expected) != 1)
                throw new AssertionError("A failed switch must not retry: " + decoded.size());
            if (!decoded.stream().allMatch(pcm -> pcm.closed))
                throw new AssertionError("A failed switch leaked a decoder");
            if (opens.get() != (drainFails ? 1 : 2))
                throw new AssertionError("Lines opened " + opens.get());
            playback.closeAsync().get(10, TimeUnit.SECONDS);
        }
    }


    /** One timed write on one line: when it was accepted and its peak. */
    private record Timed(int line, long nanos, int peak, int end, int bytes, Heard heard) {}

    /** The last sample heard on these writes; 0 when nothing was heard. */
    private static int lastHeard(java.util.List<Timed> writes) {
        var heard = writes.stream().filter(w -> w.heard().heard()).toList();
        return heard.isEmpty() ? 0 : heard.getLast().heard().lastSample();
    }

    private static long heardFrames(java.util.List<Timed> writes) {
        return writes.stream().mapToLong(w -> w.heard().frames - w.heard().discarded).sum();
    }

    private static void nap(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
    }

    /**
     * Lines paced at 16x real time for the authored mono 8 kHz streams, so two lines advance
     * together and their writes can be ordered on one timeline.
     */
    private static final class Timeline {
        final ConcurrentLinkedQueue<Timed> writes = new ConcurrentLinkedQueue<>();
        final java.util.List<Device> devices = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** Refuses a line while another is still open, like a single-line device. */
        volatile boolean singleLine;
        /** A sample rate the device does not support, as a format refusal; 0 for none. */
        volatile float refusedRate;

        SourceDataLine open(javax.sound.sampled.AudioFormat format)
                throws javax.sound.sampled.LineUnavailableException {
            if (format.getSampleRate() == refusedRate)
                throw new IllegalArgumentException("unsupported format");
            if (singleLine && devices.stream().anyMatch(d -> d.closedLines.get() == 0))
                throw new javax.sound.sampled.LineUnavailableException("one line only");
            var device = new Device();
            int index = devices.size();
            devices.add(device);
            SourceDataLine line = device.line();
            return (SourceDataLine) Proxy.newProxyInstance(
                    WorldAudioPlaybackTest.class.getClassLoader(),
                    new Class<?>[] {SourceDataLine.class}, (proxy, method, args) -> {
                        Object result = method.invoke(line, args);
                        if (method.getName().equals("write")) {
                            int length = (Integer) args[2];
                            writes.add(new Timed(index, System.nanoTime(), device.last, device.end,
                                    length, device.latest));
                            Thread.sleep(Math.max(1, length / 256)); // 16x real time
                        }
                        return result;
                    });
        }

        java.util.List<Timed> line(int index) {
            return writes.stream().filter(w -> w.line() == index).toList();
        }

        boolean closed(int index) {
            return devices.size() > index && devices.get(index).closedLines.get() > 0;
        }
    }

    /** A decoder that waits until canceled, as a stalled source would; never yields audio. */
    private static WorldAudioPlayback.Pcm stalled(BooleanSupplier canceled) throws IOException {
        while (!canceled.getAsBoolean()) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                throw new IOException("interrupted");
            }
        }
        throw new IOException("World audio decoding canceled");
    }

    private static void awaitFull(Timeline lines, int index) throws Exception {
        await(() -> lines.line(index).stream().anyMatch(w -> w.peak() >= 16000),
                "line " + index + " did not reach full volume");
    }

    /**
     * The old track holds full volume until the next one has audio; then the old ramps down while
     * the new ramps up over the same 1.5 s, overlapping at mid-fade; then the old line closes.
     */
    private static void crossfadeHoldsUntilTheNextTrackHasAudio() throws Exception {
        var lines = new Timeline();
        var decodes = new AtomicInteger();
        var changed = new AtomicLong(Long.MAX_VALUE);
        var playback = new WorldAudioPlayback(lines::open, (bytes, canceled) -> {
            // A slow decoder start, measured in the old line's writes rather than wall time so
            // a slow or coarse clock (CI runners) cannot shorten the hold.
            if (decodes.incrementAndGet() == 2) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (lines.line(0).stream().filter(w -> w.nanos() > changed.get()).count() < 6
                        && System.nanoTime() < deadline) nap(2);
            }
            return new FakePcm(Long.MAX_VALUE);
        }, status -> {});
        var music = playback.music;
        music.volume(1);
        music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), FILES);
        music.enabled(true);
        awaitFull(lines, 0);
        long change = System.nanoTime();
        changed.set(change);
        music.track(track(WorldAreaAudio.Kind.MUSIC, 200, 0), FILES);
        await(() -> lines.closed(0), "old line did not close");
        awaitFull(lines, 1);
        playback.closeAsync().get(10, TimeUnit.SECONDS);

        var old = lines.line(0);
        var next = lines.line(1);
        long started = next.getFirst().nanos();
        var held = old.stream().filter(w -> w.nanos() > change && w.nanos() < started).toList();
        if (held.size() < 5)
            throw new AssertionError("Old track must keep playing while the next loads: "
                    + held.size() + " writes");
        if (held.stream().anyMatch(w -> w.peak() < 16000))
            throw new AssertionError("Old track must hold full volume while the next loads: "
                    + held.stream().map(Timed::peak).toList());
        var fading = old.stream().filter(w -> w.nanos() >= started).toList();
        if (fading.isEmpty()) throw new AssertionError("Fades must overlap, not run in turn");
        for (int i = 1; i < fading.size(); i++)
            if (fading.get(i).peak() > fading.get(i - 1).peak())
                throw new AssertionError("Old track must only ramp down");
        if (lastHeard(fading) > 500) throw new AssertionError("Old track did not fade out");
        // The fade starts in the old line's first block that ends below full volume; that block
        // can be written just before the new track's first write is timed, so count from it.
        int fadeStart = 0;
        while (fadeStart < old.size() && old.get(fadeStart).end() >= 16000) fadeStart++;
        long fadeFrames = heardFrames(old.subList(fadeStart, old.size()));
        if (fadeFrames < 8000 * WorldAudioPlayback.FADE_SECONDS * 0.9)
            throw new AssertionError("Old fade too short: " + fadeFrames);
        var rising = next.stream().takeWhile(w -> w.peak() < 16000).toList();
        for (int i = 1; i < rising.size(); i++)
            if (rising.get(i).peak() < rising.get(i - 1).peak())
                throw new AssertionError("New track must only ramp up");
        if (next.getFirst().peak() > 6000) throw new AssertionError("New track must fade in");
        // While the new track is mid-fade the old one is still clearly audible.
        boolean overlap = false;
        int lastOld = 0x4000;
        for (var w : lines.writes) {
            if (w.line() == 0) lastOld = w.peak();
            else if (w.peak() > 2000 && w.peak() < 14000 && lastOld > 2000) overlap = true;
        }
        if (!overlap) throw new AssertionError("Fades never overlapped");
        // Equal power: the two voices' summed power stays near full through the crossfade.
        lastOld = 0x4000;
        for (var w : lines.writes) {
            if (w.line() == 0) lastOld = w.peak();
            else if (w.peak() >= 16000) break;
            else if ((double) lastOld * lastOld + (double) w.peak() * w.peak()
                    < 0.6 * 0x4000 * 0x4000)
                throw new AssertionError("Crossfade power dips: " + lastOld + " " + w.peak());
        }
    }

    /** Muting while the next track loads fades the held one out instead of holding it. */
    private static void mutingWhileHeldFadesOut() throws Exception {
        var lines = new Timeline();
        var decodes = new AtomicInteger();
        var playback = new WorldAudioPlayback(lines::open, (bytes, canceled) ->
                decodes.incrementAndGet() == 1 ? new FakePcm(Long.MAX_VALUE) : stalled(canceled),
                status -> {});
        playback.music.volume(1);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), FILES);
        playback.music.enabled(true);
        awaitFull(lines, 0);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 200, 0), FILES);
        await(() -> decodes.get() == 2, "next decoder did not start");
        long muted = System.nanoTime();
        playback.music.enabled(false);
        await(() -> lines.closed(0), "held track did not fade out when muted");
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - muted);
        if (took > WorldAudioPlayback.HOLD_MAX_MILLIS)
            throw new AssertionError("Mute waited for the hold bound: " + took);
        if (lastHeard(lines.line(0)) > 500) throw new AssertionError("Mute cut, not faded");
        playback.closeAsync().get(10, TimeUnit.SECONDS);
    }

    /** A next track that never gets audio releases the held one after the hold bound. */
    private static void holdIsBounded() throws Exception {
        var lines = new Timeline();
        var decodes = new AtomicInteger();
        var playback = new WorldAudioPlayback(lines::open, (bytes, canceled) ->
                decodes.incrementAndGet() == 1 ? new FakePcm(Long.MAX_VALUE) : stalled(canceled),
                status -> {});
        playback.music.volume(1);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), FILES);
        playback.music.enabled(true);
        awaitFull(lines, 0);
        long change = System.nanoTime();
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 200, 0), FILES);
        await(() -> lines.closed(0), "held track was never released");
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - change);
        if (took < WorldAudioPlayback.HOLD_MAX_MILLIS)
            throw new AssertionError("Released before the hold bound: " + took);
        if (lines.devices.size() != 1) throw new AssertionError("A stalled track opened a line");
        playback.closeAsync().get(10, TimeUnit.SECONDS);
    }

    /** An unavailable next track releases the held one and shows why. */
    private static void unavailableNextTrackReleasesTheHeldOne() throws Exception {
        var lines = new Timeline();
        var statuses = new ConcurrentLinkedQueue<WorldAudioPlayback.Status>();
        var playback = new WorldAudioPlayback(lines::open,
                (bytes, canceled) -> new FakePcm(Long.MAX_VALUE), statuses::add);
        playback.music.volume(1);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), FILES);
        playback.music.enabled(true);
        awaitFull(lines, 0);
        long change = System.nanoTime();
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 200, 0), (id, token) ->
                CompletableFuture.completedFuture(new WorldAreaAudio.AudioFile(id,
                        WorldAreaAudio.FileState.UNAVAILABLE, null, "Audio file #" + id
                        + " is NOT_FOUND.")));
        await(() -> lines.closed(0), "held track was not released");
        if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - change)
                >= WorldAudioPlayback.HOLD_MAX_MILLIS)
            throw new AssertionError("A failed track waited for the hold bound");
        await(() -> statuses.stream().anyMatch(s -> s.message().contains("NOT_FOUND")),
                "failure was hidden");
        playback.closeAsync().get(10, TimeUnit.SECONDS);
    }

    /**
     * A next track replaced before it has audio passes the held track on: the old one stays at
     * full volume until the newest track plays.
     */
    private static void supersededLoadsPassTheHeldTrackOn() throws Exception {
        var lines = new Timeline();
        var decodes = new AtomicInteger();
        var playback = new WorldAudioPlayback(lines::open, (bytes, canceled) ->
                switch (decodes.incrementAndGet()) {
                    case 2 -> stalled(canceled);
                    case 3 -> {
                        nap(200);
                        yield new FakePcm(Long.MAX_VALUE);
                    }
                    default -> new FakePcm(Long.MAX_VALUE);
                }, status -> {});
        playback.music.volume(1);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), FILES);
        playback.music.enabled(true);
        awaitFull(lines, 0);
        long change = System.nanoTime();
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 200, 0), FILES);
        await(() -> decodes.get() == 2, "second track did not start loading");
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 300, 0), FILES);
        await(() -> lines.closed(0), "held track was not released");
        playback.closeAsync().get(10, TimeUnit.SECONDS);
        if (lines.devices.size() != 2) throw new AssertionError("Lines " + lines.devices.size());
        long started = lines.line(1).getFirst().nanos();
        if (lines.line(0).stream().filter(w -> w.nanos() > change && w.nanos() < started)
                .anyMatch(w -> w.peak() < 16000))
            throw new AssertionError("The held track faded before the newest track played");
    }

    /** A device with one line: the old track yields quickly and the next one still plays. */
    private static void singleLineDevicesYieldTheLine() throws Exception {
        var lines = new Timeline();
        lines.singleLine = true;
        var statuses = new ConcurrentLinkedQueue<WorldAudioPlayback.Status>();
        var playback = new WorldAudioPlayback(lines::open,
                (bytes, canceled) -> new FakePcm(Long.MAX_VALUE), statuses::add);
        playback.music.volume(1);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), FILES);
        playback.music.enabled(true);
        awaitFull(lines, 0);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 200, 0), FILES);
        awaitFull(lines, 1);
        playback.closeAsync().get(10, TimeUnit.SECONDS);
        if (count(statuses, WorldAudioPlayback.OUTPUT_UNAVAILABLE) != 0)
            throw new AssertionError("A single-line device lost the next track");
        if (lastHeard(lines.line(0)) > 500)
            throw new AssertionError("The yielding track's fade was not heard");
    }

    /**
     * A replaced single-file ambience keeps looping its short file while it fades, instead of
     * stopping (and clicking) at the end of the current pass.
     */
    private static void replacedAmbienceLoopsWhileItFades() throws Exception {
        var lines = new Timeline();
        var playback = new WorldAudioPlayback(lines::open,
                (bytes, canceled) -> new FakePcm(400), status -> {});
        playback.ambience.volume(1);
        playback.ambience.track(track(WorldAreaAudio.Kind.AMBIENCE, 100, 0), FILES);
        playback.ambience.enabled(true);
        awaitFull(lines, 0);
        long change = System.nanoTime();
        playback.ambience.track(null, null);
        await(() -> lines.closed(0), "ambience did not fade out");
        playback.closeAsync().get(10, TimeUnit.SECONDS);
        var fading = lines.line(0).stream().filter(w -> w.nanos() > change).toList();
        long frames = heardFrames(fading);
        if (frames < 8000 * WorldAudioPlayback.FADE_SECONDS * 0.9)
            throw new AssertionError("Fade-out stopped with the 400-frame file: " + frames);
        if (lastHeard(fading) > 500) throw new AssertionError("Ambience was cut");
    }

    /** Closing mid-crossfade releases every decoder and line. */
    private static void closeDuringACrossfadeReleasesEverything() throws Exception {
        var lines = new Timeline();
        var streams = new ConcurrentLinkedQueue<FakePcm>();
        var playback = new WorldAudioPlayback(lines::open, (bytes, canceled) -> {
            var pcm = new FakePcm(Long.MAX_VALUE);
            streams.add(pcm);
            return pcm;
        }, status -> {});
        playback.music.volume(1);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), FILES);
        playback.music.enabled(true);
        awaitFull(lines, 0);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 200, 0), FILES);
        await(() -> lines.devices.size() == 2 && !lines.line(1).isEmpty(), "no crossfade");
        playback.closeAsync().get(10, TimeUnit.SECONDS);
        if (!streams.stream().allMatch(s -> s.closed))
            throw new AssertionError("Close left a decoder open");
        if (lines.devices.stream().anyMatch(d -> d.closedLines.get() == 0))
            throw new AssertionError("Close left a line open");
    }

    /**
     * On a single-line device a tail that is already released and fading also gives back its
     * line: mute and unmute within the fade still plays.
     */
    private static void singleLineDevicesYieldEveryTail() throws Exception {
        var lines = new Timeline();
        lines.singleLine = true;
        var statuses = new ConcurrentLinkedQueue<WorldAudioPlayback.Status>();
        var playback = new WorldAudioPlayback(lines::open,
                (bytes, canceled) -> new FakePcm(Long.MAX_VALUE), statuses::add);
        playback.music.volume(1);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), FILES);
        playback.music.enabled(true);
        awaitFull(lines, 0);
        int before = lines.line(0).size();
        playback.music.enabled(false);
        // Unmute only once the muted track is a released tail, audibly fading.
        await(() -> lines.line(0).stream().skip(before).anyMatch(w -> w.peak() < 12000),
                "muted track did not start fading");
        playback.music.enabled(true);
        awaitFull(lines, 1);
        playback.closeAsync().get(10, TimeUnit.SECONDS);
        if (count(statuses, WorldAudioPlayback.OUTPUT_UNAVAILABLE) != 0)
            throw new AssertionError("A fading tail kept the only line: " + statuses);
        if (lastHeard(lines.line(0)) > 500)
            throw new AssertionError("The yielding tail's fade was not heard");
    }

    /**
     * A format the device refuses is not a busy device: the old track fades out normally over
     * the full fade instead of yielding in 50 ms.
     */
    private static void formatRefusalsKeepTheOldTrackFading() throws Exception {
        var lines = new Timeline();
        lines.refusedRate = 16000;
        var decodes = new AtomicInteger();
        var statuses = new ConcurrentLinkedQueue<WorldAudioPlayback.Status>();
        var playback = new WorldAudioPlayback(lines::open, (bytes, canceled) ->
                decodes.incrementAndGet() == 1 ? new FakePcm(Long.MAX_VALUE)
                        : new RatePcm(16000, Long.MAX_VALUE), statuses::add);
        playback.music.volume(1);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), FILES);
        playback.music.enabled(true);
        awaitFull(lines, 0);
        long change = System.nanoTime();
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 200, 0), FILES);
        await(() -> count(statuses, WorldAudioPlayback.OUTPUT_UNAVAILABLE) == 1,
                "the refused format was hidden");
        await(() -> lines.closed(0), "old track did not fade out");
        playback.closeAsync().get(10, TimeUnit.SECONDS);
        var fading = lines.line(0).stream().filter(w -> w.nanos() > change).toList();
        if (heardFrames(fading) < 8000 * WorldAudioPlayback.FADE_SECONDS * 0.9)
            throw new AssertionError("A format refusal cut the old track short: "
                    + heardFrames(fading));
    }

    /** A track that ends while held plays out its line's buffer instead of losing it. */
    private static void aTrackEndingWhileHeldIsHeardToItsEnd() throws Exception {
        var lines = new Timeline();
        var decodes = new AtomicInteger();
        var playback = new WorldAudioPlayback(lines::open, (bytes, canceled) ->
                decodes.incrementAndGet() == 1 ? new FakePcm(8000 * 6) : stalled(canceled),
                status -> {});
        playback.music.volume(1);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), FILES);
        playback.music.enabled(true);
        awaitFull(lines, 0);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 200, 0), FILES);
        await(() -> lines.closed(0), "the held track did not end");
        var played = lines.line(0);
        long heard = heardFrames(played), written = played.stream().mapToLong(w -> w.bytes() / 2)
                .sum();
        if (written != 8000 * 6 || heard != written)
            throw new AssertionError("End of the held track was cut: " + heard + " of " + written);
        playback.closeAsync().get(10, TimeUnit.SECONDS);
    }

    /** A track replaced in the middle of its fade-in fades out from there without a jump. */
    private static void aTrackReplacedMidFadeInFadesOutSmoothly() throws Exception {
        var lines = new Timeline();
        var playback = new WorldAudioPlayback(lines::open,
                (bytes, canceled) -> new FakePcm(Long.MAX_VALUE), status -> {});
        playback.music.volume(1);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), FILES);
        playback.music.enabled(true);
        awaitFull(lines, 0);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 200, 0), FILES);
        await(() -> lines.devices.size() > 1 && lines.line(1).stream()
                .anyMatch(w -> w.peak() > 3000), "second track did not start fading in");
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 300, 0), FILES);
        awaitFull(lines, 2);
        await(() -> lines.closed(0) && lines.closed(1), "replaced tracks did not close");
        playback.closeAsync().get(10, TimeUnit.SECONDS);
        var middle = lines.line(1);
        for (int i = 1; i < middle.size(); i++)
            if (Math.abs(middle.get(i).peak() - middle.get(i - 1).peak()) > 5000)
                throw new AssertionError("Level jumped at block " + i);
        if (lastHeard(middle) > 500 || lastHeard(lines.line(0)) > 500)
            throw new AssertionError("A replaced track was cut");
    }

    /** Several replaced tracks fading at once all fade out and close. */
    private static void severalReleasedTailsFadeTogether() throws Exception {
        var lines = new Timeline();
        var playback = new WorldAudioPlayback(lines::open,
                (bytes, canceled) -> new FakePcm(Long.MAX_VALUE), status -> {});
        playback.music.volume(1);
        for (int kit = 1; kit <= 4; kit++) {
            int line = kit - 1;
            playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 100 * kit, 0), FILES);
            if (kit == 1) playback.music.enabled(true);
            await(() -> lines.devices.size() > line && !lines.line(line).isEmpty(),
                    "track " + kit + " did not start");
        }
        awaitFull(lines, 3);
        await(() -> lines.closed(0) && lines.closed(1) && lines.closed(2),
                "replaced tracks did not close");
        for (int line = 0; line < 3; line++)
            if (lastHeard(lines.line(line)) > 500)
                throw new AssertionError("Tail " + line + " was cut");
        playback.closeAsync().get(10, TimeUnit.SECONDS);
        if (lines.devices.stream().anyMatch(d -> d.closedLines.get() == 0))
            throw new AssertionError("A line stayed open");
    }

    /**
     * A next track whose stream or line fails after its first write reports it; the old track,
     * released at that write, still fades out and closes.
     */
    private static void failuresAfterTheFirstWriteReleaseTheOldTrack() throws Exception {
        for (boolean lineFails : new boolean[] {false, true}) {
            var lines = new Timeline();
            var decodes = new AtomicInteger();
            var statuses = new ConcurrentLinkedQueue<WorldAudioPlayback.Status>();
            var playback = new WorldAudioPlayback(format -> {
                var line = lines.open(format);
                if (lineFails && lines.devices.size() == 2) lines.devices.get(1).dieAfter = 2;
                return line;
            }, (bytes, canceled) -> {
                if (decodes.incrementAndGet() == 1 || lineFails) return new FakePcm(Long.MAX_VALUE);
                return new FakePcm(Long.MAX_VALUE) {
                    int reads;

                    @Override
                    public int read(byte[] buffer, int offset, int length, BooleanSupplier c)
                            throws IOException {
                        if (++reads > 2) throw new IOException("World audio decoder stalled");
                        return super.read(buffer, offset, length, c);
                    }
                };
            }, statuses::add);
            playback.music.volume(1);
            playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), FILES);
            playback.music.enabled(true);
            awaitFull(lines, 0);
            playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 200, 0), FILES);
            String expected = lineFails ? WorldAudioPlayback.OUTPUT_STOPPED
                    : "Recorded music stream failed: World audio decoder stalled";
            await(() -> count(statuses, expected) == 1, "failure was hidden: " + expected);
            await(() -> lines.closed(0), "old track did not close");
            if (lastHeard(lines.line(0)) > 500) throw new AssertionError("Old track was cut");
            playback.closeAsync().get(10, TimeUnit.SECONDS);
        }
    }

    /** Closing while a held track waits for a stalled next track releases everything. */
    private static void closeDuringAHoldWithAStalledNextTrack() throws Exception {
        var lines = new Timeline();
        var decodes = new AtomicInteger();
        var streams = new ConcurrentLinkedQueue<FakePcm>();
        var playback = new WorldAudioPlayback(lines::open, (bytes, canceled) -> {
            if (decodes.incrementAndGet() > 1) return stalled(canceled);
            var pcm = new FakePcm(Long.MAX_VALUE);
            streams.add(pcm);
            return pcm;
        }, status -> {});
        playback.music.volume(1);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), FILES);
        playback.music.enabled(true);
        awaitFull(lines, 0);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 200, 0), FILES);
        await(() -> decodes.get() == 2, "next track did not start loading");
        long closing = System.nanoTime();
        playback.closeAsync().get(10, TimeUnit.SECONDS);
        if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - closing)
                >= WorldAudioPlayback.HOLD_MAX_MILLIS)
            throw new AssertionError("Close waited for the hold bound");
        if (!streams.stream().allMatch(s -> s.closed) || !lines.closed(0))
            throw new AssertionError("Close left the held track open");
    }

    /** A fade lasts the full 1.5 s at a low slider volume too, not half of it. */
    private static void fadesLastTheSameAtAnyVolume() throws Exception {
        var lines = new Timeline();
        var playback = new WorldAudioPlayback(lines::open,
                (bytes, canceled) -> new FakePcm(Long.MAX_VALUE), status -> {});
        playback.music.volume(0.5);
        playback.music.track(track(WorldAreaAudio.Kind.MUSIC, 100, 0), FILES);
        playback.music.enabled(true);
        int full = (int) Math.round(0x4000 * 0.25);
        await(() -> lines.line(0).stream().anyMatch(w -> w.peak() >= full - 2),
                "music did not reach its level");
        playback.closeAsync().get(10, TimeUnit.SECONDS);
        long rising = lines.line(0).stream().takeWhile(w -> w.peak() < full - 2)
                .mapToLong(w -> w.bytes() / 2).sum();
        if (rising < 8000 * WorldAudioPlayback.FADE_SECONDS * 0.8)
            throw new AssertionError("Fade-in at half volume lasted " + rising + " frames");
    }

    /**
     * An authored file whose decoder has finished once {@code nearAt} frames are left, as a real
     * decoder process exits with only its buffered tail unread. Samples are {@code high} << 8.
     */
    private static final class EndingPcm extends FakePcm {
        final long nearAt;
        final int high;
        volatile long endedAt;

        EndingPcm(long frames, long nearAt, int high) {
            super(frames);
            this.nearAt = nearAt;
            this.high = high;
        }

        @Override public boolean nearEnd() { return frames - sent <= nearAt; }

        @Override
        public int read(byte[] buffer, int offset, int length, BooleanSupplier canceled)
                throws IOException {
            if (closed) throw new java.util.concurrent.CancellationException();
            if (sent >= frames) {
                if (endedAt == 0) endedAt = System.nanoTime();
                return -1;
            }
            int count = (int) Math.min(length / 2, frames - sent);
            for (int i = 0; i < count; i++) {
                buffer[offset + 2 * i] = (byte) high;
                buffer[offset + 2 * i + 1] = 0;
            }
            sent += count;
            return count * 2;
        }
    }

    /** The first write of the second file: a half-level block right after a full one, or -1. */
    private static int switchAt(java.util.List<Timed> writes) {
        for (int i = 1; i < writes.size(); i++)
            if (writes.get(i - 1).peak() >= 16000 && writes.get(i).peak() < 9000) return i;
        return -1;
    }

    /**
     * Near the end of a multi-file ambience file the next file opens ahead, so a slow open (200 ms
     * here, as on a loaded machine) is off the critical path: the next file starts within about a
     * block of the last one.
     */
    private static void lookAheadOpensNearTheEndAndHandsOverWithoutAGap() throws Exception {
        var lines = new Timeline();
        var decodes = new AtomicInteger();
        var opened = new ConcurrentLinkedQueue<Long>();
        var files = new ConcurrentLinkedQueue<EndingPcm>();
        var playback = new WorldAudioPlayback(lines::open, (bytes, canceled) -> {
            int n = decodes.incrementAndGet();
            opened.add(System.nanoTime());
            if (n >= 2) nap(200);
            // 10 s files that finish decoding with 6 s left; files alternate sample levels.
            var pcm = new EndingPcm(80_000, 48_000, n % 2 == 1 ? 0x40 : 0x20);
            files.add(pcm);
            return pcm;
        }, status -> {});
        playback.ambience.volume(1);
        playback.ambience.track(weighted(WorldAreaAudio.Kind.AMBIENCE, 100, List.of(1L, 2L),
                List.of(1, 1), 1, 0), FILES);
        playback.ambience.enabled(true);
        await(() -> switchAt(lines.line(0)) > 0, "second file did not play");
        playback.closeAsync().get(10, TimeUnit.SECONDS);
        var first = files.peek();
        var times = opened.toArray(Long[]::new);
        if (times.length < 2 || first.endedAt == 0 || times[1] >= first.endedAt)
            throw new AssertionError("The next file was not opened before the first ended");
        var writes = lines.line(0);
        int switchAt = switchAt(writes);
        long gap = TimeUnit.NANOSECONDS.toMillis(
                writes.get(switchAt).nanos() - writes.get(switchAt - 1).nanos());
        // An open on the critical path shows as a gap of about 200 ms; leave room for a busy runner.
        if (gap > 170)
            throw new AssertionError("The 200 ms open was on the critical path: " + gap + " ms");
    }

    /**
     * A look-ahead in flight is cancelled by a zone change; one already open but unused is
     * closed when the track changes or the playback closes. No decoder is left open.
     */
    private static void lookAheadsAreCancelledOrClosedWhenNotUsed() throws Exception {
        var lines = new Timeline();
        var decodes = new AtomicInteger();
        var aheadCanceled = new java.util.concurrent.CountDownLatch(1);
        var playback = new WorldAudioPlayback(lines::open, (bytes, canceled) -> {
            int n = decodes.incrementAndGet();
            if (n == 2) {
                try {
                    return stalled(canceled);
                } finally {
                    aheadCanceled.countDown();
                }
            }
            return n == 1 ? new EndingPcm(80_000, 80_000, 0x40) : new FakePcm(Long.MAX_VALUE);
        }, status -> {});
        playback.ambience.volume(1);
        playback.ambience.track(weighted(WorldAreaAudio.Kind.AMBIENCE, 100, List.of(1L, 2L),
                List.of(1, 1), 1, 0), FILES);
        playback.ambience.enabled(true);
        await(() -> decodes.get() == 2, "no look-ahead started");
        playback.ambience.track(weighted(WorldAreaAudio.Kind.AMBIENCE, 200, List.of(3L, 4L),
                List.of(1, 1), 1, 0), FILES);
        if (!aheadCanceled.await(10, TimeUnit.SECONDS))
            throw new AssertionError("A zone change left the look-ahead running");
        playback.closeAsync().get(10, TimeUnit.SECONDS);

        var streams = new ConcurrentLinkedQueue<FakePcm>();
        var unused = new WorldAudioPlayback(lines::open, (bytes, canceled) -> {
            var pcm = streams.isEmpty() ? new EndingPcm(80_000, 80_000, 0x40)
                    : new FakePcm(Long.MAX_VALUE);
            streams.add(pcm);
            return pcm;
        }, status -> {});
        unused.ambience.track(weighted(WorldAreaAudio.Kind.AMBIENCE, 100, List.of(1L, 2L),
                List.of(1, 1), 1, 0), FILES);
        unused.ambience.enabled(true);
        await(() -> streams.size() == 2, "no look-ahead opened");
        unused.closeAsync().get(10, TimeUnit.SECONDS);
        await(() -> streams.stream().allMatch(s -> s.closed), "an unused look-ahead leaked");

        // An open but unused look-ahead is closed by a track change, before any close.
        var opened = new java.util.concurrent.CopyOnWriteArrayList<FakePcm>();
        var changed = new WorldAudioPlayback(lines::open, (bytes, canceled) -> {
            var pcm = opened.isEmpty() ? new EndingPcm(80_000, 80_000, 0x40)
                    : new FakePcm(Long.MAX_VALUE);
            opened.add(pcm);
            return pcm;
        }, status -> {});
        changed.ambience.track(weighted(WorldAreaAudio.Kind.AMBIENCE, 100, List.of(1L, 2L),
                List.of(1, 1), 1, 0), FILES);
        changed.ambience.enabled(true);
        await(() -> opened.size() == 2, "no look-ahead opened before the track change");
        var lookAhead = opened.get(1);
        changed.ambience.track(weighted(WorldAreaAudio.Kind.AMBIENCE, 200, List.of(3L, 4L),
                List.of(1, 1), 1, 0), FILES);
        await(() -> lookAhead.closed, "a track change left the unused look-ahead open");
        changed.closeAsync().get(10, TimeUnit.SECONDS);

        // A look-ahead whose source read ignores cancellation does not hold the next track; it
        // is closed once its open completes.
        var stuck = new java.util.concurrent.CountDownLatch(1);
        var late = new java.util.concurrent.atomic.AtomicReference<FakePcm>();
        var opens = new AtomicInteger();
        var held = new WorldAudioPlayback(lines::open, (bytes, canceled) -> {
            int n = opens.incrementAndGet();
            if (n == 2) {
                try {
                    stuck.await(); // ignores cancellation
                } catch (InterruptedException interrupted) {
                    throw new IOException("interrupted");
                }
                var pcm = new FakePcm(Long.MAX_VALUE);
                late.set(pcm);
                return pcm;
            }
            return n == 1 ? new EndingPcm(80_000, 80_000, 0x40) : new FakePcm(Long.MAX_VALUE);
        }, status -> {});
        try {
            held.ambience.track(weighted(WorldAreaAudio.Kind.AMBIENCE, 100, List.of(1L, 2L),
                    List.of(1, 1), 1, 0), FILES);
            held.ambience.enabled(true);
            await(() -> opens.get() == 2, "no look-ahead started");
            long changedAt = System.nanoTime();
            held.ambience.track(weighted(WorldAreaAudio.Kind.AMBIENCE, 200, List.of(3L, 4L),
                    List.of(1, 1), 1, 0), FILES);
            await(() -> opens.get() >= 3, "the next track never opened");
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - changedAt);
            if (waited > 1000)
                throw new AssertionError("A stuck look-ahead held the next track for " + waited + " ms");
        } finally {
            stuck.countDown();
        }
        await(() -> late.get() != null && late.get().closed, "the late look-ahead was not closed");
        held.closeAsync().get(10, TimeUnit.SECONDS);
    }

    /** A failed look-ahead falls back to opening at the end of the file; the status recovers. */
    private static void failedLookAheadFallsBackToOpenAtTheEnd() throws Exception {
        var lines = new Timeline();
        var reads = new AtomicInteger();
        var decodes = new AtomicInteger();
        var statuses = new ConcurrentLinkedQueue<WorldAudioPlayback.Status>();
        WorldAudioPlayback.Source flaky = (id, token) -> reads.incrementAndGet() == 2
                ? CompletableFuture.completedFuture(new WorldAreaAudio.AudioFile(id,
                        WorldAreaAudio.FileState.UNAVAILABLE, null,
                        "Audio file #" + id + " is NOT_FOUND."))
                : FILES.file(id, token);
        var playback = new WorldAudioPlayback(lines::open, (bytes, canceled) -> {
            decodes.incrementAndGet();
            return new EndingPcm(4000, 4000, 0x40);
        }, statuses::add);
        playback.ambience.track(weighted(WorldAreaAudio.Kind.AMBIENCE, 100, List.of(1L, 2L),
                List.of(1, 1), 1, 0), flaky);
        playback.ambience.enabled(true);
        await(() -> decodes.get() >= 3, "playback stopped after the failed look-ahead");
        playback.closeAsync().get(10, TimeUnit.SECONDS);
        if (statuses.stream().anyMatch(s -> s.message().contains("NOT_FOUND")))
            throw new AssertionError("A quiet look-ahead failure was shown: " + statuses);
        if (statuses.stream().filter(WorldAudioPlayback.Status::playing).count() < 2)
            throw new AssertionError("Playback did not report playing again: " + statuses);
    }

    public static void main(String[] args) throws Exception {
        probeMatchesWindowsLinuxAndMonoMixerFormats();
        downmixAveragesStereoFrames();
        monoOnlyDevicesPlayDownmixedStereoAndStereoDevicesAreUnchanged();
        monoOnlyAmbienceLoopsReplayWholePasses();
        gainRampsPerFrame();
        fadesKeepKitsAndCloseOwnedResources();
        ambienceLoopsFromMemoryAndMusicWaitsRecordedSilence();
        missingSourcesAreVisibleAndOpenNoLine();
        missingDeviceIsReportedBeforeAnyFileOrDecoder();
        deadLineAndStreamFailuresStopAmbienceLooping();
        statusTextNamesNoLocalPaths();
        recordedWeightsPickFilesAndKitVolumeScalesGain();
        ambienceReopenReportsPlayingAgain();
        mixedFormatAmbienceSwitchesLinesWithoutRestarting();
        busySourceIsRetriedNotUnavailable();
        entryVolumesScaleEachFileAndSilentKitsSaySo();
        failuresDuringAFormatSwitchStopCleanly();
        crossfadeHoldsUntilTheNextTrackHasAudio();
        mutingWhileHeldFadesOut();
        holdIsBounded();
        unavailableNextTrackReleasesTheHeldOne();
        supersededLoadsPassTheHeldTrackOn();
        singleLineDevicesYieldTheLine();
        replacedAmbienceLoopsWhileItFades();
        closeDuringACrossfadeReleasesEverything();
        singleLineDevicesYieldEveryTail();
        formatRefusalsKeepTheOldTrackFading();
        aTrackEndingWhileHeldIsHeardToItsEnd();
        aTrackReplacedMidFadeInFadesOutSmoothly();
        severalReleasedTailsFadeTogether();
        failuresAfterTheFirstWriteReleaseTheOldTrack();
        closeDuringAHoldWithAStalledNextTrack();
        fadesLastTheSameAtAnyVolume();
        lookAheadOpensNearTheEndAndHandsOverWithoutAGap();
        lookAheadsAreCancelledOrClosedWhenNotUsed();
        failedLookAheadFallsBackToOpenAtTheEnd();
        System.out.println("World audio playback: fades, kit continuity, ambience loops,"
                + " recorded silence, mute, missing sources, missing device, dead lines,"
                + " path-free status, mono downmix, recorded weights and kit volume, reopen status,"
                + " mixed-format ambience and switch failures, busy-source retry, entry volumes"
                + ", crossfade hold, curves, mute, hold bound, failure, superseded loads, single-line"
                + " devices, looping fade-out, yield of every tail, format refusal, heard ends,"
                + " mid-fade replacement, several tails, failures after audio, equal-power"
                + " fixed-time fades, near-end look-ahead and owned close passed");
    }
}
