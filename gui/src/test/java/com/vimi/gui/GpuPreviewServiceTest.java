package com.vimi.gui;

import com.vibeminer.rendering.models.PreviewMesh;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Deterministic process/scene lifecycle proof; never starts a graphics process or uses user data.
 */
public final class GpuPreviewServiceTest {
    private GpuPreviewServiceTest() {}

    private static void worldBackendOverrideIsExplicitAndBounded() {
        var backend = com.vibeminer.rendering.bgfx.WorldStreamTransport.Backend.class;
        require(WorldStreamGpuOwner.backend(null).name().equals("DEFAULT"), "Unset backend");
        require(WorldStreamGpuOwner.backend(" ").name().equals("DEFAULT"), "Blank backend");
        require(WorldStreamGpuOwner.backend("Vulkan").name().equals("VULKAN"), "Vulkan backend");
        require(WorldStreamGpuOwner.backend("dx11").name().equals("D3D11"), "D3D11 alias");
        require(backend.getEnumConstants().length == 4, "Backend override covers every backend");
        try {
            WorldStreamGpuOwner.backend("opengl");
            throw new AssertionError("Unknown GPU backend override must be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }

    /** Starts only `java -version`: a mistyped option fails here, not in the user's helper. */
    private static void helperAndAppJvmOptionsAreAcceptedByThisRuntime() throws Exception {
        var helper = GpuPreviewService.HELPER_JVM_OPTIONS;
        require(helper.contains("-XX:+UseZGC") && helper.stream().anyMatch(o -> o.startsWith("-Xmx")),
                "Helper gets the low-pause collector and its own bounded heap");
        var options = new java.util.ArrayList<>(helper);
        Path shared = Path.of("jvm-options.txt");
        // JavaExec runs in the gui project directory, beside the shared option file.
        require(Files.isRegularFile(shared), "Shared app JVM options beside the gui project");
        for (String line : Files.readAllLines(shared)) {
            if (!line.isEmpty() && line.charAt(0) == '﻿') line = line.substring(1);
            line = line.replaceFirst("(^|\\s)#.*$", "").strip();
            if (!line.isEmpty()) options.add(line);
        }
        require(options.contains("-XX:+UseZGC"), "Shared app options read");
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(options);
        command.add("-version");
        Path log = Files.createTempFile("vimi-jvm-options-", ".txt");
        try {
            // Output goes to a file, so the bounded wait below really bounds the check.
            var process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
            boolean exited = process.waitFor(30, TimeUnit.SECONDS);
            if (!exited) process.destroyForcibly();
            require(exited && process.exitValue() == 0,
                    "JVM rejected " + options + ": " + Files.readString(log));
        } finally {
            Files.deleteIfExists(log);
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("vimi-gpu-service-test-");
        try {
            require(
                    new GpuPreviewService.ViewRequest(1, 640, 480, 0, 0, 1, 10)
                            .command()
                            .endsWith(" 10\n"),
                    "Qualified material flag crosses bounded control protocol");
            try {
                new GpuPreviewService.ViewRequest(1, 640, 480, 0, 0, 1, 16);
                throw new AssertionError("Unknown control flags must be rejected");
            } catch (IllegalArgumentException expected) {
            }
            worldBackendOverrideIsExplicitAndBounded();
            helperAndAppJvmOptionsAreAcceptedByThisRuntime();
            latestFrameHandoffNeverRepublishesOlderWork();
            inlineFramesAndCoalescedControls(root);
            terrainAndModelsShareOwnerAndKeepControlsTyped(root);
            streamingUsesTheSameOwnerAndWaitsHeldStarterDrain(root);
            streamingAdapterSeparatesPendingSessionFromActualDrain(root);
            statusListenerErrorCannotReleaseStreamingOwnership(root);
            emptyTexturesKeepNativeHeightFallbackAndRejectedBatchesClose(root);
            terrainTextureAcknowledgementsStayBoundToQualifiedSourceAndDrainClaims(root);
            terrainReplacementWaitsForAcceptedFrameButNotTerminalFailure(root);
            normalExitAndOwnerIsolation(root);
            cancelDuringPreparationDoesNotLaunch(root);
            canceledDeliveryStopsOnlyItsRequest(root);
            closeDuringProcessStartDrainsLateChild(root);
            replacementHasOneChildAndIgnoresOldOwner(root);
            newestPendingRequestReplacesIntermediateScenes(root);
            startupTimeoutStopsUnresponsiveChild(root);
            readyWithoutFramesTimesOut(root);
            unkillableChildAndBlockedStreamCloseStayBounded(root);
            forcedStopAndBadProtocol(root);
            sizeBoundAndStartFailureCleanScratch(root);
        } finally {
            try (var files = Files.walk(root)) {
                for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(file);
            }
        }
        System.out.println(
                "GPU preview lifecycle: bounded scene preparation, isolated owners, replacement,"
                        + " cancellation, process drain and fallback passed");
    }

    private static void latestFrameHandoffNeverRepublishesOlderWork() {
        var scheduled = new java.util.ArrayDeque<Runnable>();
        var seen = new java.util.ArrayList<Integer>();
        var schedules = new AtomicInteger();
        var delivery = new AtomicReference<ModelPreviewView.LatestDelivery<Integer>>();
        delivery.set(
                new ModelPreviewView.LatestDelivery<>(
                        task -> {
                            if (schedules.incrementAndGet() == 2) delivery.get().submit(3);
                            scheduled.add(task);
                            require(scheduled.size() <= 1, "At most one pending UI frame delivery");
                        },
                        value -> {
                            seen.add(value);
                            if (value == 1) delivery.get().submit(2);
                        }));
        delivery.get().submit(1);
        scheduled.remove().run();
        require(seen.equals(List.of(1)), "First frame drained before completion handoff");
        scheduled.remove().run();
        require(
                seen.equals(List.of(1, 3)) && scheduled.isEmpty(),
                "Newer publication during handoff wins without older-value reinsertion");
    }

    private static void statusListenerErrorCannotReleaseStreamingOwnership(Path root) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var modelEntered = new CountDownLatch(1);
        var directory = new AtomicReference<Path>();
        var service = new GpuPreviewService(root, GpuPreviewServiceTest::write,
                (scene, frames) -> { modelEntered.countDown(); return new FakeProcess(true); },
                path -> new GpuPreviewService.Frames() {
                    public GpuPreviewService.Frame readLatest(long after) { return null; }
                    public void close() {}
                }, Duration.ofSeconds(2), Duration.ofMillis(200), arguments -> {
                    directory.set(Path.of(arguments.get(1)));
                    entered.countDown();
                    release.await();
                    throw new IOException("Authored late no-child failure");
                });
        var worldOwner = new Object();
        try {
            service.openStreaming(worldOwner,
                    new com.vibeminer.rendering.bgfx.WorldStreamTransport.Spec("ab".repeat(32), 1,
                            com.vibeminer.rendering.bgfx.WorldStreamTransport.Backend.DEFAULT),
                    session -> {}, status -> {
                        if (status.state() == GpuPreviewService.State.STOPPING) {
                            throw new AssertionError("Authored status delivery error");
                        }
                    });
            await(entered);
            service.open(new Object(), mesh(), null, Set.of(), ignored -> {});
            require(!modelEntered.await(1, TimeUnit.SECONDS),
                    "Listener Error must not release GPU owner while prior Starter/mapping is held");
        } finally {
            release.countDown();
            service.closeAsync().get(5, TimeUnit.SECONDS);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (directory.get() != null && Files.exists(directory.get()) && System.nanoTime() < until) {
                Thread.sleep(5);
            }
            require(directory.get() == null || !Files.exists(directory.get()), "Owned old startup eventually drains");
        }
    }

    private static void streamingUsesTheSameOwnerAndWaitsHeldStarterDrain(Path root)
            throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var directory = new AtomicReference<Path>();
        var modelStarts = new AtomicInteger();
        var model = new FakeProcess(true);
        var service = new GpuPreviewService(root, GpuPreviewServiceTest::write,
                (scene, frames) -> {
                    require(!Files.exists(directory.get()),
                            "Model must wait actual streaming preparation/process drain");
                    modelStarts.incrementAndGet();
                    return model;
                }, path -> new GpuPreviewService.Frames() {
                    public GpuPreviewService.Frame readLatest(long after) { return null; }
                    public void close() {}
                }, Duration.ofSeconds(2), Duration.ofMillis(200), arguments -> {
                    directory.set(Path.of(arguments.get(1)));
                    entered.countDown();
                    release.await();
                    throw new IOException("Authored late no-child start failure");
                });
        Object worldOwner = new Object(), modelOwner = new Object();
        var states = new ConcurrentLinkedQueue<GpuPreviewService.State>();
        var world = service.openStreaming(worldOwner,
                new com.vibeminer.rendering.bgfx.WorldStreamTransport.Spec("ab".repeat(32), 1,
                        com.vibeminer.rendering.bgfx.WorldStreamTransport.Backend.DEFAULT),
                session -> { throw new AssertionError("Failed start cannot publish session"); },
                status -> states.add(status.state()));
        try {
            await(entered);
            require(Files.exists(directory.get().resolve("frames.wsf")),
                    "Held Starter retains its owned mapping");
            service.closeOwner(new Object()).get(1, TimeUnit.SECONDS);
            require(!world.isDone(), "An unrelated owner cannot retire active streaming work");
            var next = service.open(modelOwner, mesh(), null, Set.of(), ignored -> {});
            require(modelStarts.get() == 0, "No second renderer starts beside a held Starter");
            require(!next.isDone(), "Successor remains pending while old ownership is held");
            var closing = service.closeOwner(worldOwner);
            require(!closing.isDone(), "Stop request is not an actual resource drain");
            require(Files.exists(directory.get().resolve("frames.wsf")),
                    "Superseding a source never deletes a held launch mapping");
            release.countDown();
            await(model.started);
            require(modelStarts.get() == 1 && !Files.exists(directory.get()),
                    "Exactly one successor starts after actual prior cleanup");
            require(!states.contains(GpuPreviewService.State.READY),
                    "Protocol initialization alone never claims a displayed World frame");
            world.handle((value, error) -> null).get(2, TimeUnit.SECONDS);
            service.closeOwner(modelOwner).get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            service.closeAsync().get(3, TimeUnit.SECONDS);
        }
    }

    private static void streamingAdapterSeparatesPendingSessionFromActualDrain(Path root)
            throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var directory = new AtomicReference<Path>();
        var service = new GpuPreviewService(root, GpuPreviewServiceTest::write,
                (scene, frames) -> { throw new AssertionError("No model launch requested"); },
                path -> new GpuPreviewService.Frames() {
                    public GpuPreviewService.Frame readLatest(long after) { return null; }
                    public void close() {}
                }, Duration.ofSeconds(2), Duration.ofMillis(200), arguments -> {
                    directory.set(Path.of(arguments.get(1)));
                    entered.countDown();
                    release.await();
                    throw new IOException("Authored held start without a child");
                });
        try {
            var first = new WorldStreamGpuOwner(service, "ab".repeat(32));
            await(entered);
            var second = new WorldStreamGpuOwner(service, "cd".repeat(32));
            var queuedSession = second.session();
            second.closeAsync().get(1, TimeUnit.SECONDS);
            require(queuedSession.isCompletedExceptionally(),
                    "Canceled queued owner cannot leave its Session future pending");
            var actualDrain = first.closeAsync();
            require(first.session().isCompletedExceptionally(),
                    "Controller can observe stop before a held starter returns");
            require(!actualDrain.isDone() && Files.exists(directory.get()),
                    "Session failure does not forge held startup resource drain");
            release.countDown();
            expectFailure(actualDrain, "Actual close retains the late startup failure");
            require(!Files.exists(directory.get()),
                    "Actual adapter close waits for owned mapping retirement");
            expectFailure(first.closeAsync(), "Retired owner retains its earlier failed lifetime");
            var third = new WorldStreamGpuOwner(service, "ef".repeat(32));
            try {
                third.session().get(3, TimeUnit.SECONDS);
                throw new AssertionError("Authored failed start cannot produce a Session");
            } catch (java.util.concurrent.ExecutionException expected) {
                // The request fails spontaneously, before the adapter receives any close call.
            }
            service.closeAsync().get(3, TimeUnit.SECONDS);
            expectFailure(third.closeAsync(),
                    "First close after service teardown preserves original failed request");
        } finally {
            release.countDown();
            service.closeAsync().get(3, TimeUnit.SECONDS);
        }
    }

    private static void terrainAndModelsShareOwnerAndKeepControlsTyped(Path root) throws Exception {
        var scene = new com.vibeminer.rendering.world.TerrainScene(List.of(
                com.vibeminer.rendering.world.TerrainScene.Tile.copyOf(17, 21,
                        new float[] {10, -1, 0, 10, 1, 0, 10, 0, 1}, new int[] {0, 1, 2}, 1)));
        var camera = new com.vibeminer.rendering.world.TerrainCamera(1, 2, 3, .2, -.1);
        try {
            new GpuPreviewService.TerrainRequest(1, 801, 500, camera);
            throw new AssertionError("Terrain allocation cap must be checked");
        } catch (IllegalArgumentException expected) {
        }
        var terrainChild = new FakeProcess(true);
        var modelChild = new FakeProcess(true);
        var launches = new AtomicInteger();
        var fixture = service(root, GpuPreviewServiceTest::write, file -> {
            if (launches.getAndIncrement() == 0) {
                var restored = com.vibeminer.rendering.bgfx.GpuTerrainSceneFile.read(file);
                require(restored.tiles().getFirst().x() == 17
                        && restored.tiles().getFirst().y() == 21,
                        "Terrain payload keeps source tuple rather than using model geometry");
                return terrainChild;
            }
            require(!terrainChild.isAlive(), "Terrain drains before a model child may start");
            require(Files.readString(file).equals("authored scene"),
                    "Model still uses the legacy injected writer");
            return modelChild;
        });
        Object terrainOwner = new Object(), modelOwner = new Object();
        try {
            var terrainClosed = fixture.openTerrain(terrainOwner, scene, ignored -> {}, ignored -> {});
            fixture.update(terrainOwner, new GpuPreviewService.ViewRequest(100, 32, 32, 0, 0, 1, 0));
            fixture.updateTerrain(terrainOwner, new GpuPreviewService.TerrainRequest(1, 800, 500, camera));
            await(terrainChild.started);
            // The fake child records controls one character at a time, so poll for the whole line:
            // a prefix match could snapshot a control the writer is still in the middle of.
            String worldControl = "WORLD 1 800 500 1.0 2.0 3.0 0.2 -0.1\n";
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!terrainChild.controls.toString().contains(worldControl)
                    && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            String terrainControls = terrainChild.controls.toString();
            require(terrainControls.contains(worldControl)
                    && !terrainControls.contains("VIEW"),
                    "Orbit controls cannot advance or corrupt free-flight revision; controls: "
                            + terrainControls.strip().replace('\n', '|'));
            fixture.open(modelOwner, mesh(), null, Set.of(), ignored -> {});
            fixture.updateTerrain(modelOwner, new GpuPreviewService.TerrainRequest(100, 32, 32, camera));
            fixture.update(modelOwner, new GpuPreviewService.ViewRequest(2, 32, 32, 0, 0, 1, 0));
            await(modelChild.started);
            terrainClosed.get(2, TimeUnit.SECONDS);
            fixture.closeOwner(terrainOwner).get(2, TimeUnit.SECONDS);
            require(modelChild.isAlive(), "Retired terrain owner cannot close the model successor");
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!completeLine(modelChild.controls.toString(), "VIEW 2 ")
                    && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            String modelControls = modelChild.controls.toString();
            require(completeLine(modelControls, "VIEW 2 ") && !modelControls.contains("WORLD"),
                    "Free-flight controls cannot corrupt an orbit session; controls: "
                            + modelControls.strip().replace('\n', '|'));
        } finally {
            fixture.closeAsync().get(3, TimeUnit.SECONDS);
        }
        require(launches.get() == 2 && !terrainChild.isAlive() && !modelChild.isAlive(),
                "One shared native lifecycle drains both renderer kinds");
    }

    private static void emptyTexturesKeepNativeHeightFallbackAndRejectedBatchesClose(Path root) throws Exception {
        var tile = new com.vibeminer.formats.world.WorldFormats.Tile(17, 21, 1, 0, 0);
        var scene = new com.vibeminer.rendering.world.TerrainScene(List.of(
                com.vibeminer.rendering.world.TerrainScene.Tile.copyOf(17, 21,
                        new float[] {10, -1, 0, 10, 1, 0, 10, 0, 1}, new int[] {0, 1, 2}, 1)));
        for (boolean nullScene : List.of(true, false)) {
            var batch = com.vibeminer.concepts.world.WorldTerrainTextureSource.BatchResult.unsupported(List.of(tile));
            try {
                WorldTextureSnapshot.prepare(nullScene ? null : scene, batch,
                        nullScene ? () -> false : null);
                throw new AssertionError("Invalid preparation arguments must be rejected");
            } catch (NullPointerException expected) {
            }
            try {
                batch.retainAtlas(17, 21);
                throw new AssertionError("Rejected preparation must close its consumed batch");
            } catch (IllegalStateException expected) {
            }
        }
        var empty = WorldTextureSnapshot.prepare(scene,
                com.vibeminer.concepts.world.WorldTerrainTextureSource.BatchResult.unsupported(List.of(tile)),
                () -> false);
        var child = new FakeProcess(true);
        var ready = new CountDownLatch(1);
        var fixture = service(root, GpuPreviewServiceTest::write, file -> child);
        try (empty) {
            fixture.openTerrain(new Object(), scene, empty,
                    status -> { if (status.state() == GpuPreviewService.State.READY) ready.countDown(); },
                    ignored -> {});
            await(child.started);
            child.send("VIMI_GPU HELLO Vulkan\nVIMI_GPU READY Vulkan\n");
            await(ready);
            require(empty.atlasCount() == 0, "Empty textures use height mode without requiring an annex ACK");
        } finally {
            fixture.closeAsync().get(3, TimeUnit.SECONDS);
        }
    }

    private static void terrainTextureAcknowledgementsStayBoundToQualifiedSourceAndDrainClaims(Path root) throws Exception {
        for (String mode : List.of("READY", "PARTIAL", "UNAVAILABLE", "MISSING", "DUPLICATE", "WRONG_SCENE", "OVERCLAIM")) {
            boolean partial = mode.equals("PARTIAL") || mode.equals("OVERCLAIM");
            try (var source = new TerrainGpuPreviewTextureFixture(partial)) {
                var snapshot = source.snapshot();
                var child = new FakeProcess(true);
                var accepted = new AtomicReference<GpuPreviewService.Status>();
                var fixture = service(root, GpuPreviewServiceTest::write, file -> {
                    require(Files.size(file.resolveSibling("terrain.vatx")) > (1 << 20),
                            "Qualified annex is written before child starts");
                    return child;
                });
                try {
                    Object owner = new Object();
                    var drained = fixture.openTerrain(owner, source.scene, snapshot, status -> {
                        if (status.state() == GpuPreviewService.State.READY) accepted.set(status);
                    }, ignored -> {});
                    var releasedBeforeDelivery = drained.handle((value, failure) -> {
                        require(source.stats().leaseClaims() == 1 && source.stats().borrowPins() == 0,
                                "Drain completion observes all request claims already retired " + mode);
                        return null;
                    });
                    await(child.started);
                    require(source.stats().leaseClaims() == 2 && source.stats().borrowPins() == 0,
                            "Request owns exactly one additional claim after synchronous annex borrow");
                    var code = com.vibeminer.rendering.bgfx.TerrainTextureStatus.Code.NONE;
                    var acknowledgement = com.vibeminer.rendering.bgfx.TerrainTextureStatus.of(
                            mode.equals("UNAVAILABLE") ? 0 : 1,
                            mode.equals("WRONG_SCENE") ? 2 : 1,
                            mode.equals("UNAVAILABLE") ? 0 : mode.equals("PARTIAL") ? 128 : 256,
                            mode.equals("UNAVAILABLE") ? com.vibeminer.rendering.bgfx.TerrainTextureStatus.Code.CAPABILITY : code);
                    String line = acknowledgement.protocolLine() + "\n";
                    child.send("VIMI_GPU HELLO Vulkan\n"
                            + (mode.equals("MISSING") ? "" : line)
                            + (mode.equals("DUPLICATE") ? line : "")
                            + "VIMI_GPU READY Vulkan\n");
                    boolean valid = List.of("READY", "PARTIAL", "UNAVAILABLE").contains(mode);
                    if (valid) {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                        while (accepted.get() == null && System.nanoTime() < deadline) Thread.sleep(2);
                        require(accepted.get() != null && acknowledgement.equals(accepted.get().textures()),
                                "Native readiness carries the exact admitted texture outcome " + mode);
                        fixture.closeOwner(owner).get(3, TimeUnit.SECONDS);
                        drained.get(3, TimeUnit.SECONDS);
                    } else {
                        try {
                            drained.get(3, TimeUnit.SECONDS);
                            throw new AssertionError("Invalid texture acknowledgement accepted " + mode);
                        } catch (java.util.concurrent.ExecutionException expected) {
                        }
                        require(accepted.get() == null, "Invalid texture protocol never becomes ready " + mode);
                    }
                    releasedBeforeDelivery.get(3, TimeUnit.SECONDS);
                    require(source.stats().leaseClaims() == 1 && source.stats().borrowPins() == 0,
                            "All terminal request paths release their claim " + mode);
                } finally {
                    fixture.closeAsync().get(3, TimeUnit.SECONDS);
                    snapshot.close();
                }
                require(source.stats().leaseClaims() == 0, "Caller closes its independent claim");
            }
        }
    }

    private static void terrainReplacementWaitsForAcceptedFrameButNotTerminalFailure(Path root) throws Exception {
        var scene = new com.vibeminer.rendering.world.TerrainScene(List.of(
                com.vibeminer.rendering.world.TerrainScene.Tile.copyOf(17,21,
                        new float[] {10,-1,0,10,1,0,10,0,1},new int[] {0,1,2},1)));
        var child = new FakeProcess(true);
        var queued = new ConcurrentLinkedQueue<Runnable>();
        var available = new AtomicReference<GpuPreviewService.Frame>();
        var accepted = new AtomicInteger();
        var failed = new AtomicInteger();
        var service = new GpuPreviewService(root, GpuPreviewServiceTest::write,
                (file, frames) -> child, file -> new GpuPreviewService.Frames() {
                    public GpuPreviewService.Frame readLatest(long after) {
                        var value = available.get();
                        return value != null && value.sequence() > after ? value : null;
                    }
                    public void close() {}
                },Duration.ofSeconds(2),Duration.ofMillis(100));
        var preview = new TerrainGpuPreview(service,queued::add, ignored -> {},
                frame -> { accepted.incrementAndGet(); return true; },failed::incrementAndGet);
        try {
            require(preview.canReplaceScene(), "Unstarted native scene does not gate preparation");
            preview.request(scene,new com.vibeminer.rendering.world.TerrainCamera(1,2,3,0,0),32,32);
            await(child.started);
            child.send("VIMI_GPU HELLO Vulkan\nVIMI_GPU READY Vulkan\n");
            require(!preview.canReplaceScene(), "Starting scene retains first-frame opportunity");
            available.set(new GpuPreviewService.Frame(1,1,32,32,java.nio.IntBuffer.wrap(new int[1024])));
            pump(queued, () -> accepted.get() == 1);
            require(preview.canReplaceScene(), "Only accepted frame releases replacement barrier");
            preview.hide();
            require(preview.canReplaceScene(), "Hidden/software scene never strands replacement");
        } finally {
            preview.close();
            service.closeAsync().get(3,TimeUnit.SECONDS);
        }
        var errorChild = new FakeProcess(true);
        var broken = service(root,GpuPreviewServiceTest::write, file -> errorChild);
        var error = new TerrainGpuPreview(broken,queued::add,ignored -> {},frame -> false,failed::incrementAndGet);
        try {
            error.request(scene,new com.vibeminer.rendering.world.TerrainCamera(1,2,3,0,0),32,32);
            await(errorChild.started);
            errorChild.send("VIMI_GPU ERROR AUTHORED_FAILURE\n");
            pump(queued, () -> failed.get() > 0);
            require(error.canReplaceScene(), "Terminal failure releases barrier without a frame");
        } finally {
            error.close();
            broken.closeAsync().get(3,TimeUnit.SECONDS);
        }
    }

    private static void pump(ConcurrentLinkedQueue<Runnable> queued, java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean()) {
            Runnable next;
            while ((next=queued.poll()) != null) next.run();
            if (System.nanoTime()>deadline) throw new AssertionError("Scheduled lifecycle did not settle");
            Thread.sleep(5);
        }
    }

    private static PreviewMesh mesh() {
        return new PreviewMesh(
                new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0},
                new int[] {0, 1, 2},
                List.of(new PreviewMesh.Part(1, 0, 3)));
    }

    private static GpuPreviewService service(
            Path root, GpuPreviewService.SceneWriter writer, GpuPreviewService.Starter starter) {
        return new GpuPreviewService(
                root, writer, starter, Duration.ofSeconds(2), Duration.ofMillis(10));
    }

    private static void write(
            Path file,
            PreviewMesh mesh,
            com.vibeminer.rendering.models.MeshAppearance appearance,
            Set<Integer> parts)
            throws IOException {
        Files.writeString(file, "authored scene", StandardCharsets.UTF_8);
    }

    private static void unkillableChildAndBlockedStreamCloseStayBounded(Path root)
            throws Exception {
        var release = new CountDownLatch(1);
        var streamCloseEntered = new CountDownLatch(1);
        var alive = new java.util.concurrent.atomic.AtomicBoolean(true);
        var launches = new AtomicInteger();
        var scene = new AtomicReference<Path>();
        var child =
                new FakeProcess(false) {
                    @Override
                    public boolean isAlive() {
                        return alive.get();
                    }

                    @Override
                    public boolean waitFor(long timeout, TimeUnit unit) {
                        return !alive.get();
                    }

                    @Override
                    public void destroy() {}

                    @Override
                    public Process destroyForcibly() {
                        forced.incrementAndGet();
                        return this;
                    }

                    @Override
                    public OutputStream getOutputStream() {
                        return new OutputStream() {
                            public void write(int ignored) {}

                            public void close() throws IOException {
                                streamCloseEntered.countDown();
                                try {
                                    release.await();
                                } catch (InterruptedException error) {
                                    throw new IOException(error);
                                }
                            }
                        };
                    }
                };
        var service =
                service(
                        root,
                        GpuPreviewServiceTest::write,
                        path -> {
                            launches.incrementAndGet();
                            scene.set(path);
                            return child;
                        });
        try {
            Object owner = new Object();
            var first = service.open(owner, mesh(), null, Set.of(1), ignored -> {});
            await(child.started);
            child.send("VIMI_GPU HELLO Vulkan\nVIMI_GPU READY Vulkan\n");
            var close = service.closeOwner(owner);
            await(streamCloseEntered);
            var successor = service.open(new Object(), mesh(), null, Set.of(1), ignored -> {});
            expectFailure(
                    first, "Unkillable child reports failure without waiting for stream close");
            expectFailure(close, "Owner drain remains bounded despite blocked stream close");
            expectFailure(successor, "No successor can launch while previous child lives");
            expectFailure(service.closeAsync(), "Application drain reports unkillable child");
            require(
                    launches.get() == 1 && child.isAlive(),
                    "One live child prevents any replacement");
            require(
                    Files.exists(scene.get())
                            && Files.exists(scene.get().resolveSibling("frames.vgpu")),
                    "Scene and frame scratch remain while unkillable child lives");
        } finally {
            alive.set(false);
            release.countDown();
            ((FakeProcess) child).finish();
            if (scene.get() != null) {
                try (var retained = Files.walk(scene.get().getParent())) {
                    for (var file : retained.sorted(java.util.Comparator.reverseOrder()).toList())
                        Files.deleteIfExists(file);
                }
            }
        }
    }

    private static void expectFailure(CompletableFuture<Void> future, String message)
            throws Exception {
        try {
            future.get(3, TimeUnit.SECONDS);
            throw new AssertionError(message);
        } catch (java.util.concurrent.ExecutionException expected) {
            // The failure itself is the bounded lifecycle result, not a test timeout.
        }
    }

    private static void readyWithoutFramesTimesOut(Path root) throws Exception {
        var child = new FakeProcess(true);
        var service =
                new GpuPreviewService(
                        root,
                        GpuPreviewServiceTest::write,
                        scene -> child,
                        Duration.ofMillis(100),
                        Duration.ofMillis(10));
        Object owner = new Object();
        var request = service.open(owner, mesh(), null, Set.of(1), ignored -> {});
        service.update(owner, new GpuPreviewService.ViewRequest(1, 32, 32, 0, 0, 1.15, 2));
        await(child.started);
        child.send("VIMI_GPU HELLO Vulkan\nVIMI_GPU READY Vulkan\n");
        try {
            request.get(2, TimeUnit.SECONDS);
            throw new AssertionError("READY without any mapped frame must fail");
        } catch (java.util.concurrent.ExecutionException expected) {
            require(!child.isAlive(), "Frame startup timeout drains renderer");
        }
        service.closeAsync().get(2, TimeUnit.SECONDS);
    }

    private static void inlineFramesAndCoalescedControls(Path root) throws Exception {
        var child = new FakeProcess(true);
        var frames = new ConcurrentLinkedQueue<GpuPreviewService.Frame>();
        var received = new ConcurrentLinkedQueue<Long>();
        var released = new AtomicInteger();
        var fixture =
                new GpuPreviewService(
                        root,
                        GpuPreviewServiceTest::write,
                        (scene, mapping) -> child,
                        path ->
                                new GpuPreviewService.Frames() {
                                    public GpuPreviewService.Frame readLatest(long after) {
                                        return frames.poll();
                                    }

                                    public void close() {
                                        released.incrementAndGet();
                                    }
                                },
                        Duration.ofSeconds(2),
                        Duration.ofMillis(20));
        Object owner = new Object();
        var request =
                fixture.open(
                        owner,
                        mesh(),
                        null,
                        Set.of(1),
                        ignored -> {},
                        frame -> received.add(frame.controlRevision()));
        for (int revision = 1; revision <= 100; revision++)
            fixture.update(
                    owner,
                    new GpuPreviewService.ViewRequest(
                            revision, 640, 480, revision * .01, 0, 1.15, 2));
        await(child.started);
        child.send("VIMI_GPU HELLO Vulkan\nVIMI_GPU READY Vulkan\n");
        frames.add(
                new GpuPreviewService.Frame(
                        1,
                        101,
                        1,
                        1,
                        java.nio.IntBuffer.wrap(new int[] {0xff123456}).asReadOnlyBuffer()));
        frames.add(
                new GpuPreviewService.Frame(
                        2,
                        99,
                        1,
                        1,
                        java.nio.IntBuffer.wrap(new int[] {0xff123456}).asReadOnlyBuffer()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((!received.contains(99L) || !child.controls.toString().contains("VIEW 100 "))
                && System.nanoTime() < deadline) Thread.sleep(10);
        require(
                received.equals(new ConcurrentLinkedQueue<>(List.of(99L)))
                        || (received.size() == 1 && received.contains(99L)),
                "Future revisions rejected while a lagging camera frame can be displayed");
        require(child.controls.toString().contains("VIEW 100 "), "Latest control reaches child");
        fixture.closeOwner(owner).get(2, TimeUnit.SECONDS);
        request.get(2, TimeUnit.SECONDS);
        require(released.get() == 1, "Frame mapping closes once after renderer termination");
        fixture.closeAsync().get(2, TimeUnit.SECONDS);
    }

    private static void normalExitAndOwnerIsolation(Path root) throws Exception {
        var child = new FakeProcess(true);
        var path = new AtomicReference<Path>();
        var service =
                service(
                        root,
                        GpuPreviewServiceTest::write,
                        scene -> {
                            path.set(scene);
                            return child;
                        });
        var ready = new CountDownLatch(1);
        Object owner = new Object();
        var request =
                service.open(
                        owner,
                        mesh(),
                        null,
                        Set.of(1),
                        status -> {
                            if (status.state() == GpuPreviewService.State.READY) ready.countDown();
                        });
        await(child.started);
        child.send("VIMI_GPU HELLO OpenGL\nVIMI_GPU READY OpenGL\n");
        await(ready);
        service.closeOwner(new Object()).get(2, TimeUnit.SECONDS);
        require(child.isAlive(), "An unrelated owner cannot close a preview");
        require(Files.exists(path.get()), "Scene stays available while child lives");
        service.closeOwner(owner).get(2, TimeUnit.SECONDS);
        request.get(2, TimeUnit.SECONDS);
        require(!child.isAlive(), "Owner close drains process");
        require(!Files.exists(path.get()), "Scene removed only after exit");
        service.closeAsync().get(2, TimeUnit.SECONDS);
        require(
                service.open(owner, mesh(), null, Set.of(), ignored -> {})
                        .isCompletedExceptionally(),
                "Closed application rejects native launches");
    }

    private static void cancelDuringPreparationDoesNotLaunch(Path root) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var launches = new AtomicInteger();
        var service =
                service(
                        root,
                        (file, mesh, appearance, parts) -> {
                            entered.countDown();
                            try {
                                await(release);
                            } catch (Exception e) {
                                throw new IOException(e);
                            }
                            write(file, mesh, appearance, parts);
                        },
                        scene -> {
                            launches.incrementAndGet();
                            return new FakeProcess(true);
                        });
        Object owner = new Object();
        service.open(owner, mesh(), null, Set.of(), ignored -> {});
        await(entered);
        var closing = service.closeOwner(owner);
        require(!closing.isDone(), "Cancellation waits for owned scene preparation");
        release.countDown();
        closing.get(2, TimeUnit.SECONDS);
        service.closeAsync().get(2, TimeUnit.SECONDS);
        require(launches.get() == 0, "Canceled preparation does not start child");
    }

    private static void canceledDeliveryStopsOnlyItsRequest(Path root) throws Exception {
        var child = new FakeProcess(true);
        var service = service(root, GpuPreviewServiceTest::write, scene -> child);
        var request = service.open(new Object(), mesh(), null, Set.of(), ignored -> {});
        await(child.started);
        require(request.cancel(false), "Caller may abandon a native preview request");
        service.closeAsync().get(2, TimeUnit.SECONDS);
        require(!child.isAlive(), "Canceled delivery cannot detach a child from cleanup");
    }

    private static void closeDuringProcessStartDrainsLateChild(Path root) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var child = new FakeProcess(true);
        var path = new AtomicReference<Path>();
        var service =
                service(
                        root,
                        GpuPreviewServiceTest::write,
                        scene -> {
                            path.set(scene);
                            entered.countDown();
                            try {
                                await(release);
                            } catch (Exception e) {
                                throw new IOException(e);
                            }
                            return child;
                        });
        service.open(new Object(), mesh(), null, Set.of(), ignored -> {});
        await(entered);
        var closing = service.closeAsync();
        require(!closing.isDone(), "Shutdown owns late process launch");
        require(Files.exists(path.get()), "Scratch retained until late launch is drained");
        release.countDown();
        closing.get(2, TimeUnit.SECONDS);
        require(!child.isAlive(), "Late-created child is stopped");
        require(!Files.exists(path.get()), "Late process scratch removed after termination");
    }

    private static void replacementHasOneChildAndIgnoresOldOwner(Path root) throws Exception {
        var children = new ConcurrentLinkedQueue<FakeProcess>();
        var first = new FakeProcess(true);
        var second = new FakeProcess(true);
        var launches = new AtomicInteger();
        var service =
                service(
                        root,
                        GpuPreviewServiceTest::write,
                        scene -> {
                            if (launches.getAndIncrement() == 0) {
                                children.add(first);
                                return first;
                            }
                            require(
                                    !first.isAlive(),
                                    "Replacement starts after previous child exits");
                            children.add(second);
                            return second;
                        });
        Object a = new Object(), b = new Object();
        service.open(a, mesh(), null, Set.of(), ignored -> {});
        await(first.started);
        service.open(b, mesh(), null, Set.of(), ignored -> {});
        await(second.started);
        service.closeOwner(a).get(2, TimeUnit.SECONDS);
        require(second.isAlive(), "Obsolete panel cannot close new owner's window");
        service.closeAsync().get(2, TimeUnit.SECONDS);
        require(children.size() == 2 && !second.isAlive(), "Application drains selected preview");
    }

    private static void newestPendingRequestReplacesIntermediateScenes(Path root) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new FakeProcess(true);
        var last = new FakeProcess(true);
        var calls = new AtomicInteger();
        var service =
                service(
                        root,
                        GpuPreviewServiceTest::write,
                        scene -> {
                            if (calls.getAndIncrement() == 0) {
                                entered.countDown();
                                try {
                                    await(release);
                                } catch (Exception error) {
                                    throw new IOException(error);
                                }
                                return first;
                            }
                            return last;
                        });
        service.open(new Object(), mesh(), null, Set.of(), ignored -> {});
        await(entered);
        var superseded = service.open(new Object(), mesh(), null, Set.of(1), ignored -> {});
        service.open(new Object(), mesh(), null, Set.of(1), ignored -> {});
        superseded.get(2, TimeUnit.SECONDS);
        release.countDown();
        await(last.started);
        service.closeAsync().get(2, TimeUnit.SECONDS);
        require(
                calls.get() == 2,
                "Intermediate pending scene is coalesced without a process launch");
    }

    private static void startupTimeoutStopsUnresponsiveChild(Path root) throws Exception {
        var child = new FakeProcess(true);
        var service =
                new GpuPreviewService(
                        root,
                        GpuPreviewServiceTest::write,
                        scene -> child,
                        Duration.ofMillis(20),
                        Duration.ofMillis(10));
        try {
            service.open(new Object(), mesh(), null, Set.of(), ignored -> {})
                    .get(2, TimeUnit.SECONDS);
            throw new AssertionError("Missing first frame must time out");
        } catch (java.util.concurrent.ExecutionException expected) {
            /* Readiness timeout. */
        }
        service.closeAsync().get(2, TimeUnit.SECONDS);
        require(!child.isAlive(), "Readiness timeout cannot leave a child running");
    }

    private static void forcedStopAndBadProtocol(Path root) throws Exception {
        var child = new FakeProcess(false);
        var service = service(root, GpuPreviewServiceTest::write, scene -> child);
        var failed = new CountDownLatch(1);
        var request =
                service.open(
                        new Object(),
                        mesh(),
                        null,
                        Set.of(),
                        status -> {
                            if (status.state() == GpuPreviewService.State.FAILED)
                                failed.countDown();
                        });
        await(child.started);
        child.send("VIMI_GPU HELLO Noop\nVIMI_GPU READY Noop\n");
        await(failed);
        try {
            request.get(3, TimeUnit.SECONDS);
            throw new AssertionError("Noop cannot be presented as GPU success");
        } catch (java.util.concurrent.ExecutionException expected) {
            /* Expected safe fallback. */
        }
        service.closeAsync().get(2, TimeUnit.SECONDS);
        require(
                child.forced.get() == 1 && !child.isAlive(),
                "Unresponsive child is forcibly drained");
    }

    private static void sizeBoundAndStartFailureCleanScratch(Path root) throws Exception {
        var launches = new AtomicInteger();
        var service =
                service(
                        root,
                        (file, mesh, appearance, parts) -> {
                            try (var channel =
                                    FileChannel.open(
                                            file,
                                            StandardOpenOption.CREATE_NEW,
                                            StandardOpenOption.WRITE)) {
                                channel.position(41L << 20);
                                channel.write(ByteBuffer.wrap(new byte[] {1}));
                            }
                        },
                        scene -> {
                            launches.incrementAndGet();
                            throw new IOException("must not launch");
                        });
        try {
            service.open(new Object(), mesh(), null, Set.of(), ignored -> {})
                    .get(2, TimeUnit.SECONDS);
            throw new AssertionError("Oversize rejected");
        } catch (java.util.concurrent.ExecutionException expected) {
            /* Expected input bound. */
        }
        service.closeAsync().get(2, TimeUnit.SECONDS);
        require(launches.get() == 0, "Oversized scene never launches native code");
        var broken =
                service(
                        root,
                        GpuPreviewServiceTest::write,
                        scene -> {
                            throw new IOException("fixture failure");
                        });
        try {
            broken.open(new Object(), mesh(), null, Set.of(), ignored -> {})
                    .get(2, TimeUnit.SECONDS);
            throw new AssertionError("Start failure reported");
        } catch (java.util.concurrent.ExecutionException expected) {
            /* Expected startup fallback. */
        }
        broken.closeAsync().get(2, TimeUnit.SECONDS);
        try (var files = Files.list(root)) {
            require(files.findAny().isEmpty(), "Every terminated attempt reclaims scratch");
        }
    }

    private static void await(CountDownLatch latch) throws Exception {
        require(latch.await(3, TimeUnit.SECONDS), "Fixture reached expected lifecycle boundary");
    }

    /** Whether {@code controls} holds a whole control line starting with {@code prefix}. */
    private static boolean completeLine(String controls, String prefix) {
        int start = controls.indexOf(prefix);
        return start >= 0 && controls.indexOf('\n', start) >= 0;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static class FakeProcess extends Process {
        private final PipedInputStream output = new PipedInputStream();
        private final PipedOutputStream events;
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final boolean graceful;
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicInteger forced = new AtomicInteger();
        final StringBuffer controls = new StringBuffer();
        private final StringBuilder command = new StringBuilder();

        FakeProcess(boolean graceful) throws IOException {
            this.graceful = graceful;
            events = new PipedOutputStream(output);
        }

        void send(String text) throws IOException {
            events.write(text.getBytes(StandardCharsets.UTF_8));
            events.flush();
        }

        private void finish() {
            exit.complete(0);
            try {
                events.close();
            } catch (IOException ignored) {
            }
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int value) {
                    synchronized (command) {
                        controls.append((char) value);
                        if (value == '\n') {
                            if (graceful && command.toString().equals("CLOSE")) finish();
                            command.setLength(0);
                        } else command.append((char) value);
                    }
                }
            };
        }

        @Override
        public InputStream getInputStream() {
            started.countDown();
            return output;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            try {
                return exit.get();
            } catch (java.util.concurrent.ExecutionException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                exit.get(timeout, unit);
                return true;
            } catch (java.util.concurrent.TimeoutException e) {
                return false;
            } catch (java.util.concurrent.ExecutionException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public int exitValue() {
            if (!exit.isDone()) throw new IllegalThreadStateException();
            return exit.join();
        }

        @Override
        public boolean isAlive() {
            return !exit.isDone();
        }

        @Override
        public void destroy() {
            if (graceful) finish();
        }

        @Override
        public Process destroyForcibly() {
            forced.incrementAndGet();
            finish();
            return this;
        }
    }
}
