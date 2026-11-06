package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.world.BuildWorld;
import com.vibeminer.concepts.world.WorldAreaAudio;
import com.vibeminer.concepts.world.WorldCurrentArea;
import com.vibeminer.concepts.world.WorldService;
import com.vibeminer.formats.world.WorldFormats;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The World audio controls' selection logic with an authored world, a manual settle delay, a
 * manual UI queue and a set clock: debounce, dedup, day/night by clock with a manual override,
 * and plan failures. No display, sound device or game data.
 */
public final class WorldAudioControlsTest {
    private WorldAudioControlsTest() {}

    private static final class SetClock extends Clock {
        Instant now;

        SetClock(LocalTime time) {
            set(time);
        }

        void set(LocalTime time) {
            now = time.atDate(java.time.LocalDate.of(2026, 1, 1)).toInstant(ZoneOffset.UTC);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private record Request(long areaId, WorldAreaAudio.Variant variant,
            CompletableFuture<WorldAreaAudio.Plan> result) {}

    /** Plans are answered by the test; every request is recorded. */
    private static final class World implements BuildWorld {
        final List<Request> requests = new ArrayList<>();

        @Override
        public synchronized CompletableFuture<WorldAreaAudio.Plan> areaAudio(long mapId,
                WorldCurrentArea.Result area, WorldAreaAudio.Variant variant,
                Cancellation token) {
            var result = new CompletableFuture<WorldAreaAudio.Plan>();
            requests.add(new Request(area.areaId(), variant, result));
            return result;
        }

        @Override
        public CompletableFuture<WorldAreaAudio.AudioFile> audioFile(long id, Cancellation t) {
            return new CompletableFuture<>();
        }

        Request last() {
            return requests.getLast();
        }

        @Override public CompletableFuture<WorldService.Catalog> catalog(Cancellation t) {
            throw new UnsupportedOperationException();
        }

        @Override public CompletableFuture<WorldService.Inventory> inventory(
                WorldService.MapInfo map, Cancellation t) {
            throw new UnsupportedOperationException();
        }

        @Override public CompletableFuture<WorldService.LoadedTile> tile(
                WorldService.MapInfo map, WorldFormats.Tile tile, Cancellation t) {
            throw new UnsupportedOperationException();
        }

        @Override public CompletableFuture<Void> closeAsync() {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final WorldCurrentArea.Index INDEX = WorldCurrentArea.Index.of(List.of(
            new WorldService.Area(10, "Authored zone", 0, 0),
            new WorldService.Area(11, "Authored subzone", 10, 0)));

    private static WorldCurrentArea.Result area(long id) {
        return WorldCurrentArea.resolve(0, id, INDEX);
    }

    private static WorldAreaAudio.Plan plan(long areaId, WorldAreaAudio.Variant variant,
            long kit) {
        var music = new WorldAreaAudio.Track(WorldAreaAudio.Kind.MUSIC, areaId, "Authored zone",
                5, kit, List.of(kit * 10), 0, 0);
        return new WorldAreaAudio.Plan(0, areaId, variant,
                new WorldAreaAudio.Selection(WorldAreaAudio.Kind.MUSIC,
                        WorldAreaAudio.State.RECORDED, music, "Recorded zone music"),
                new WorldAreaAudio.Selection(WorldAreaAudio.Kind.AMBIENCE,
                        WorldAreaAudio.State.NONE_RECORDED, null, "No ambience"),
                List.of());
    }

    /** One director with manual settle delay, UI queue and clock. */
    private static final class Rig {
        final SetClock clock;
        final ArrayDeque<Runnable> ui = new ArrayDeque<>();
        final List<String> texts = new ArrayList<>();
        final WorldAudioPlayback playback;
        final WorldAudioDirector director;
        int debounces;

        Rig(LocalTime time) {
            clock = new SetClock(time);
            // No output device: tracks are published but never decoded or played.
            playback = new WorldAudioPlayback(() -> 0, format -> {
                throw new javax.sound.sampled.LineUnavailableException("none");
            }, (bytes, canceled) -> {
                throw new java.io.IOException("unused");
            }, status -> {});
            director = new WorldAudioDirector(playback, clock, () -> debounces++,
                    task -> {
                        synchronized (ui) {
                            ui.add(task);
                        }
                    }, texts::add);
        }

        void drain() {
            while (true) {
                Runnable task;
                synchronized (ui) {
                    task = ui.poll();
                }
                if (task == null) return;
                task.run();
            }
        }

        WorldAreaAudio.Track music() {
            return playback.music.track();
        }

        String text() {
            return texts.getLast();
        }

        void close() throws Exception {
            director.close();
            playback.closeAsync().get(10, TimeUnit.SECONDS);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void debounceResolvesOnlyTheSettledArea() throws Exception {
        var rig = new Rig(LocalTime.NOON);
        var world = new World();
        rig.director.toggle(WorldAreaAudio.Kind.MUSIC, true);
        int before = rig.debounces;
        rig.director.area(world, 0, area(10));
        rig.director.area(world, 0, area(11));
        check(rig.debounces == before + 2, "each area change restarts the settle delay");
        check(world.requests.isEmpty(), "nothing resolves before the delay settles");
        rig.director.settled();
        check(world.requests.size() == 1 && world.last().areaId() == 11,
                "only the settled area resolves: " + world.requests);
        world.last().result().complete(plan(11, WorldAreaAudio.Variant.DAY, 100));
        check(rig.music() == null, "plans publish on the UI thread only");
        rig.drain();
        check(rig.music() != null && rig.music().soundKitId() == 100, "plan was not published");
        check(rig.text().equals("Recorded zone music"), "status " + rig.text());
        rig.close();
    }

    private static void unchangedAreasAndRepeatedSettlesAreDeduplicated() throws Exception {
        var rig = new Rig(LocalTime.NOON);
        var world = new World();
        rig.director.toggle(WorldAreaAudio.Kind.MUSIC, true);
        rig.director.area(world, 0, area(10));
        int debounces = rig.debounces;
        for (int frame = 0; frame < 100; frame++) rig.director.area(world, 0, area(10));
        check(rig.debounces == debounces, "an unchanged area must not restart the delay");
        rig.director.settled();
        rig.director.settled();
        check(world.requests.size() == 1, "a settled area resolves once: " + world.requests);
        world.last().result().complete(plan(10, WorldAreaAudio.Variant.DAY, 100));
        rig.drain();
        rig.director.settled();
        check(world.requests.size() == 1, "a resolved area resolves again");

        // With both channels off nothing resolves.
        rig.director.toggle(WorldAreaAudio.Kind.MUSIC, false);
        rig.director.area(world, 0, area(11));
        rig.director.settled();
        check(world.requests.size() == 1, "disabled audio resolved an area");
        rig.close();
    }

    private static void dayAndNightFollowTheClockUnlessChosen() throws Exception {
        check(WorldAudioDirector.variantAt(LocalTime.of(5, 59)) == WorldAreaAudio.Variant.NIGHT,
                "before dawn is night");
        check(WorldAudioDirector.variantAt(WorldAudioDirector.DAY_STARTS)
                == WorldAreaAudio.Variant.DAY, "dawn is day");
        check(WorldAudioDirector.variantAt(WorldAudioDirector.NIGHT_STARTS)
                == WorldAreaAudio.Variant.NIGHT, "dusk is night");
        var rig = new Rig(LocalTime.of(23, 0));
        var world = new World();
        rig.director.toggle(WorldAreaAudio.Kind.MUSIC, true);
        rig.director.area(world, 0, area(10));
        rig.director.settled();
        check(world.last().variant() == WorldAreaAudio.Variant.NIGHT, "clock night not used");
        world.last().result().complete(plan(10, WorldAreaAudio.Variant.NIGHT, 200));
        rig.drain();

        // Time passing within the slot changes nothing.
        rig.clock.set(LocalTime.of(2, 0));
        int debounces = rig.debounces;
        rig.director.tick();
        check(rig.debounces == debounces, "same slot rescheduled");

        // Dawn switches the slot for the same area.
        rig.clock.set(LocalTime.of(6, 30));
        rig.director.tick();
        rig.director.settled();
        check(world.requests.size() == 2 && world.last().variant() == WorldAreaAudio.Variant.DAY,
                "dawn did not resolve the day slot: " + world.requests);
        world.last().result().complete(plan(10, WorldAreaAudio.Variant.DAY, 100));
        rig.drain();
        check(rig.music().soundKitId() == 100, "day kit was not published");

        // A manual slot overrides the clock, and the clock slot can be chosen again.
        rig.director.slot(WorldAudioDirector.Slot.NIGHT);
        rig.director.settled();
        check(world.last().variant() == WorldAreaAudio.Variant.NIGHT, "manual night ignored");
        world.last().result().complete(plan(10, WorldAreaAudio.Variant.NIGHT, 200));
        rig.drain();
        rig.clock.set(LocalTime.NOON);
        rig.director.tick();
        rig.director.settled();
        check(world.requests.size() == 3, "the clock overrode a manual slot");
        rig.director.slot(WorldAudioDirector.Slot.CLOCK);
        rig.director.settled();
        check(world.requests.size() == 4 && world.last().variant() == WorldAreaAudio.Variant.DAY,
                "clock slot not restored: " + world.requests);
        rig.close();
    }

    private static void failedPlansKeepPlayingAndRetry() throws Exception {
        var rig = new Rig(LocalTime.NOON);
        var world = new World();
        rig.director.toggle(WorldAreaAudio.Kind.MUSIC, true);
        rig.director.area(world, 0, area(10));
        rig.director.settled();
        world.last().result().complete(plan(10, WorldAreaAudio.Variant.DAY, 100));
        rig.drain();
        var playing = rig.music();

        // A subzone of the same kit whose plan fails (a full source queue) keeps the kit playing.
        rig.director.area(world, 0, area(11));
        rig.director.settled();
        int debounces = rig.debounces;
        world.last().result().completeExceptionally(new WorldAreaAudio.QueueFull(null));
        rig.drain();
        check(rig.music() == playing, "a failed plan faded out the playing kit");
        check(rig.debounces == debounces + 1, "a failed plan was not retried");
        check(rig.text().contains("retrying"), "status " + rig.text());
        rig.director.settled();
        check(world.requests.size() == 3 && world.last().areaId() == 11, "retry not issued");
        world.last().result().complete(plan(11, WorldAreaAudio.Variant.DAY, 100));
        rig.drain();
        check(rig.music() == playing, "the unchanged kit was replaced");

        // Repeated failures stop the tracks and wait for the next change.
        rig.director.area(world, 0, area(10));
        for (int attempt = 0; attempt <= WorldAudioDirector.PLAN_RETRIES; attempt++) {
            rig.director.settled();
            world.last().result().completeExceptionally(new IllegalStateException("closed"));
            rig.drain();
        }
        int requests = world.requests.size();
        check(rig.music() == null, "repeated failures kept the tracks");
        check(rig.text().equals("Recorded area audio unavailable"), "status " + rig.text());
        rig.director.settled();
        check(world.requests.size() == requests, "gave-up area retried without a change");
        rig.director.toggle(WorldAreaAudio.Kind.MUSIC, false);
        rig.director.toggle(WorldAreaAudio.Kind.MUSIC, true);
        rig.director.settled();
        check(world.requests.size() == requests + 1, "re-enabling did not try again");

        // A stale completion after a newer request is ignored.
        var stale = world.last();
        rig.director.area(world, 0, area(11));
        rig.director.settled();
        stale.result().complete(plan(10, WorldAreaAudio.Variant.DAY, 300));
        rig.drain();
        check(rig.music() == null, "a stale plan was published");
        rig.close();
    }

    private static void closedDirectorIgnoresEverything() throws Exception {
        var rig = new Rig(LocalTime.NOON);
        var world = new World();
        rig.director.toggle(WorldAreaAudio.Kind.MUSIC, true);
        rig.director.area(world, 0, area(10));
        rig.director.settled();
        var pending = world.last();
        rig.close();
        pending.result().complete(plan(10, WorldAreaAudio.Variant.DAY, 100));
        rig.drain();
        rig.director.area(world, 0, area(11));
        rig.director.settled();
        check(world.requests.size() == 1 && rig.music() == null, "closed director acted");
    }

    public static void main(String[] args) throws Exception {
        debounceResolvesOnlyTheSettledArea();
        unchangedAreasAndRepeatedSettlesAreDeduplicated();
        dayAndNightFollowTheClockUnlessChosen();
        failedPlansKeepPlayingAndRetry();
        closedDirectorIgnoresEverything();
        System.out.println("World audio controls: debounce, dedup, clock day/night with manual"
                + " override, failed-plan retry, stale plans and close passed");
    }
}
