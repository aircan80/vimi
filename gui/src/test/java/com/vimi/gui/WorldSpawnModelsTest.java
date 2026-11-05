package com.vimi.gui;

import com.vibeminer.access.Cancellation;
import com.vibeminer.concepts.creatures.CreatureModelPolicy;
import com.vibeminer.concepts.creatures.CreatureModelPolicy.Choice;
import com.vibeminer.concepts.creatures.CreatureModelPolicy.Reason;
import com.vibeminer.concepts.creatures.CreatureModelPolicy.State;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless checks of the spawn creature model resolver: off-FX resolution, the FX-owned cache,
 * revision retirement, failures and the inspect/status disclosure. Authored outcomes only.
 */
public final class WorldSpawnModelsTest {
    private static final String SOURCE = "client Creature → CreatureDisplayInfo → CreatureModelData · build 1.60.1.70532";

    private WorldSpawnModelsTest() {}

    public static void main(String[] args) throws Exception {
        resolvesOffTheFxSideAndCaches();
        retiredRevisionsNeverPublish();
        failuresAreUnknownAndClicksJumpTheQueue();
        disclosure();
        System.out.println("World spawn models: off-FX resolution, cache, retirement, failures and disclosure passed");
    }

    /** Runs posted FX work on the test thread. */
    static final class Fx {
        final LinkedBlockingQueue<Runnable> posted = new LinkedBlockingQueue<>();

        void drain(int expected) throws InterruptedException {
            for (int i = 0; i < expected; i++) {
                var task = posted.poll(10, TimeUnit.SECONDS);
                require(task != null, "Posted FX work arrives");
                task.run();
            }
        }
    }

    static WorldSpawnModels.Resolved resolved(long npc, Choice choice, String path) {
        return new WorldSpawnModels.Resolved(npc, choice, path, SOURCE);
    }

    static Choice qualified(long file) {
        return new Choice(State.QUALIFIED, Reason.SINGLE_DISPLAY, 1, 10, 20L, file, "single");
    }

    static Choice ambiguous(int displays) {
        return new Choice(State.AMBIGUOUS, Reason.SEVERAL_DISPLAYS, displays, null, null, null,
                displays + " displays declared [1, 2]; the client chooses one per spawn, so no single model is claimed");
    }

    static Choice unresolved(Reason reason) {
        return new Choice(State.UNRESOLVED, reason, 1, 4, null, null, "Display 4 stops: no CreatureDisplayInfo record");
    }

    private static void resolvesOffTheFxSideAndCaches() throws Exception {
        var fx = new Fx();
        var threads = new ConcurrentHashMap<Long, String>();
        var calls = new ArrayList<Long>();
        var models = new WorldSpawnModels((npc, token) -> {
            threads.put(npc, Thread.currentThread().getName());
            synchronized (calls) {
                calls.add(npc);
            }
            return npc == 1 ? resolved(npc, qualified(123456), "creature/wolf/wolf.m2")
                    : npc == 2 ? resolved(npc, ambiguous(2), null)
                    : resolved(npc, unresolved(Reason.DISPLAY_ABSENT), null);
        }, () -> "r1", fx.posted::add);
        var notified = new ArrayList<Long>();
        models.listen(notified::add);
        models.request(List.of(1L, 2L, 3L, 1L));
        fx.drain(3);
        require(notified.equals(List.of(1L, 2L, 3L)), "Each NPC resolves once, in request order: " + notified);
        require(threads.values().stream().allMatch("world-spawn-models"::equals), "Lookups run on the model worker");
        require(models.cached(1).orElseThrow().modelPath().equals("creature/wolf/wolf.m2"), "Qualified path is cached");
        models.request(List.of(1L, 2L, 3L));
        require(fx.posted.poll(200, TimeUnit.MILLISECONDS) == null, "Cached NPCs are not looked up again");
        var counts = models.count(List.of(1L, 1L, 2L, 3L, 4L));
        require(counts.count(State.QUALIFIED) == 2 && counts.count(State.AMBIGUOUS) == 1
                && counts.count(State.UNRESOLVED) == 1 && counts.pending() == 1,
                "Counts are per marker, with unresolved NPCs pending: " + counts);
        String status = WorldSpawnOverlay.modelSummary(counts);
        require(status.equals(" · models (not drawn yet): 2 qualified, 1 ambiguous, 1 unresolved (1 display absent), 1 resolving"),
                "Status shows per-state and per-reason counts: " + status);
        models.close();
        models.request(List.of(9L));
        require(fx.posted.poll(200, TimeUnit.MILLISECONDS) == null, "A closed resolver starts no lookup");
    }

    private static void retiredRevisionsNeverPublish() throws Exception {
        var fx = new Fx();
        var revision = new AtomicReference<Object>("r1");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var canceled = new AtomicReference<Boolean>();
        var models = new WorldSpawnModels((npc, token) -> {
            if (npc == 5) {
                entered.countDown();
                release.await(10, TimeUnit.SECONDS);
                canceled.compareAndSet(null, token.isCanceled());
            }
            return resolved(npc, qualified(npc * 10), null);
        }, revision::get, fx.posted::add);
        models.request(List.of(5L));
        require(entered.await(10, TimeUnit.SECONDS), "Lookup started");
        revision.set("r2");
        models.request(List.of(5L, 6L));
        release.countDown();
        fx.drain(1);
        require(Boolean.TRUE.equals(canceled.get()), "A changed revision cancels the running lookup");
        // The retired result is dropped; the queue then resolves the current revision's requests.
        fx.drain(2);
        require(models.cached(5).orElseThrow().choice().modelFileDataId() == 50
                && models.cached(6).isPresent(), "Current revision outcomes are cached after the retired one is dropped");
        revision.set("r3");
        require(models.cached(5).isEmpty(), "A new revision empties the cache");
        models.close();
    }

    private static void failuresAreUnknownAndClicksJumpTheQueue() throws Exception {
        var fx = new Fx();
        var gate = new CountDownLatch(1);
        var order = new ArrayList<Long>();
        var models = new WorldSpawnModels((npc, token) -> {
            if (npc == 1) gate.await(10, TimeUnit.SECONDS);
            synchronized (order) {
                order.add(npc);
            }
            if (npc == 3) throw new IllegalStateException("table read failed");
            return resolved(npc, qualified(npc), null);
        }, () -> "r", fx.posted::add);
        models.request(List.of(1L, 2L, 3L, 4L));
        models.prioritize(4);
        gate.countDown();
        fx.drain(4);
        require(order.equals(List.of(1L, 4L, 2L, 3L)), "A clicked NPC is resolved next: " + order);
        var failed = models.cached(3).orElseThrow().choice();
        require(failed.state() == State.UNKNOWN && failed.reason() == Reason.NO_CREATURE_DATA
                && failed.detail().contains("table read failed"), "A failed lookup is an unknown outcome: " + failed);
        var unopened = WorldSpawnModels.build(() -> null, () -> null, "1.60.1.70532").resolve(7, Cancellation.NONE);
        require(unopened.choice().state() == State.UNKNOWN && unopened.source().contains("build 1.60.1.70532")
                && unopened.source().contains(CreatureModelPolicy.POLICY), "No open creature data is unknown: " + unopened);
        models.close();
    }

    private static void disclosure() {
        var spawns = WorldSpawnOverlayTest.index(List.of(WorldSpawnOverlayTest.spawn(1, 3, -640.5, -4120.5, 41.3)));
        var spawn = spawns.near(0, -640.5, -4120.5, 10, 4).placed().getFirst();
        var npc = spawns.npc(1).orElseThrow();
        String qualified = WorldPick.describeSpawn(spawn, npc, "Eastern Kingdoms",
                resolved(1, qualified(123456), "creature/wolf/wolf.m2"), false);
        require(qualified.contains("model QUALIFIED: FileDataID 123456 creature/wolf/wolf.m2 · display 10 → CreatureModelData 20")
                && qualified.contains("source " + SOURCE) && qualified.contains("not drawn yet"),
                "Qualified model file, name and source are shown: " + qualified);
        String nameless = WorldPick.describeModel(resolved(1, qualified(99), null));
        require(nameless.contains("FileDataID 99 (name unknown)"), "A missing listfile name is unknown: " + nameless);
        String several = WorldPick.describeModel(resolved(1, ambiguous(3), null));
        require(several.startsWith("model AMBIGUOUS (3 displays): "), "Ambiguous count is shown: " + several);
        String stopped = WorldPick.describeModel(resolved(1, unresolved(Reason.DISPLAY_ABSENT), null));
        require(stopped.startsWith("model UNRESOLVED (display absent): Display 4 stops"), "Refusal reason is shown: " + stopped);
        String unknown = WorldPick.describeModel(resolved(1, CreatureModelPolicy.unknown("Creature data is not open"), null));
        require(unknown.startsWith("model UNKNOWN (no creature data): Creature data is not open"), "Unknown is shown: " + unknown);
        require(WorldPick.describeSpawn(spawn, npc, null, null, true).contains("model: resolving…"),
                "A pending outcome says so");
        require(!WorldPick.describeSpawn(spawn, npc, null).contains("model"),
                "Without a resolver no model line is invented");
        require(WorldSpawnOverlay.modelSummary(new WorldSpawnModels.Counts(java.util.Map.of(), 4))
                        .equals(" · models (not drawn yet): 4 resolving"), "Only pending outcomes");
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
