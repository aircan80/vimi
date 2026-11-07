package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Late objects through the actual parent transport and a scripted child process that reads the
 * actual objects file. Control files next to the session directory hold or fail the child's objects
 * work, so every interleaving below is deterministic.
 */
@Timeout(WorldStreamTestChildren.TEST_TIMEOUT_SECONDS)
@SuppressWarnings("try")
class WorldStreamLateObjectsTransportTest {
    @TempDir Path directory;

    @Test
    void terrainCommitsWhileLateObjectsAreHeldAndThenAttach() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var first = fixture.stage(7, 19);
            fixture.commit(first);
            fixture.hold();
            var attaching =
                    fixture.session.attachObjects(first, WorldStreamObjectsFileTest.small());
            fixture.until(() -> Files.exists(fixture.control("objects-held")));
            assertTrue(Files.exists(fixture.objectsFile(first)), "Advertised file stays owned");
            // A second delivery is refused while one is in flight, without touching terrain.
            var busy =
                    assertThrows(
                            ExecutionException.class,
                            () ->
                                    fixture.session
                                            .attachObjects(
                                                    first, WorldStreamObjectsFileTest.small())
                                            .get(2, TimeUnit.SECONDS));
            assertInstanceOf(WorldStreamTransport.PreparationRefusal.class, busy.getCause());
            // The next tile's terrain stages and commits while the objects are still held.
            var second = fixture.stage(7, 20);
            fixture.commit(first, second);
            assertFalse(attaching.isDone());
            fixture.release();
            assertSame(
                    WorldStreamTransport.ObjectsOutcome.ATTACHED,
                    attaching.get(5, TimeUnit.SECONDS));
            assertSame(WorldStreamTransport.ExteriorObjects.DRAWN, first.exteriorObjects());
            assertSame(WorldStreamTransport.ExteriorObjects.NONE, second.exteriorObjects());
            fixture.until(() -> !Files.exists(fixture.objectsFile(first)));
            // A second request for the same resident fails only that future, never the session.
            var misuse =
                    assertThrows(
                            ExecutionException.class,
                            () ->
                                    fixture.session
                                            .attachObjects(
                                                    first, WorldStreamObjectsFileTest.small())
                                            .get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, misuse.getCause());
            assertFalse(fixture.session.completion().isDone());
            // The lane is free again for the next resident.
            assertSame(
                    WorldStreamTransport.ObjectsOutcome.ATTACHED,
                    fixture.session
                            .attachObjects(second, WorldStreamObjectsFileTest.small())
                            .get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void retiredResidentsAreDroppedAndReusedSlotsDropStaleObjects() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var first = fixture.stage(7, 19);
            fixture.commit(first);
            var second = fixture.stage(7, 20);
            fixture.commit(second);
            // Retired by the parent before delivery: no command, DROPPED, file removed.
            assertSame(
                    WorldStreamTransport.ObjectsOutcome.DROPPED,
                    fixture.session
                            .attachObjects(first, WorldStreamObjectsFileTest.small())
                            .get(5, TimeUnit.SECONDS));
            assertFalse(fixture.commands().contains("OBJECTS"));
            assertFalse(Files.exists(fixture.objectsFile(first)));
            assertSame(WorldStreamTransport.ExteriorObjects.NONE, first.exteriorObjects());
            // Retired by a commit while the child holds the objects: the child drops them.
            fixture.hold();
            var stale = fixture.session.attachObjects(second, WorldStreamObjectsFileTest.small());
            fixture.until(() -> Files.exists(fixture.control("objects-held")));
            var third = fixture.stage(7, 21);
            fixture.commit(third);
            fixture.release();
            assertSame(WorldStreamTransport.ObjectsOutcome.DROPPED, stale.get(5, TimeUnit.SECONDS));
            assertSame(WorldStreamTransport.ExteriorObjects.NONE, second.exteriorObjects());
            fixture.until(() -> !Files.exists(fixture.objectsFile(second)));
        }
    }

    @Test
    void childFailureStaysIsolatedToTheObjects() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var first = fixture.stage(7, 19);
            fixture.commit(first);
            // The renderer's reason is on stderr before the failure is reported.
            fixture.until(() -> !fixture.stderrTail().isEmpty());
            Files.writeString(fixture.control("fail-objects"), "fail");
            assertSame(
                    WorldStreamTransport.ObjectsOutcome.FAILED,
                    fixture.session
                            .attachObjects(first, WorldStreamObjectsFileTest.small())
                            .get(5, TimeUnit.SECONDS));
            assertSame(WorldStreamTransport.ExteriorObjects.FAILED, first.exteriorObjects());
            // The first renderer objects failure logs the renderer's redacted reason.
            String record = fixture.session.objectsDiagnostic().get(5, TimeUnit.SECONDS);
            assertTrue(
                    record.startsWith(
                            "World renderer could not draw exterior objects (1 tile this"
                                    + " session)"),
                    record);
            assertTrue(record.contains("synthetic shader failure at <path>"), record);
            assertTrue(record.contains("(world-wmo/spirv, program)"), record);
            assertFalse(record.contains("tester"), record);
            // Terrain keeps streaming and committing after the failed objects.
            var second = fixture.stage(7, 20);
            fixture.commit(first, second);
            assertFalse(fixture.session.completion().isDone());
        }
    }

    @Test
    void inlineObjectsFailureLogsTheRenderersReason() throws Exception {
        try (var fixture = new Fixture(directory)) {
            // Inline objects the renderer draws are not a failure.
            var drawn = fixture.stage(7, 18, WorldStreamObjectsFileTest.small());
            assertSame(WorldStreamTransport.ExteriorObjects.DRAWN, drawn.exteriorObjects());
            fixture.commit(drawn);
            // The renderer's reason is on stderr before the failure is reported.
            fixture.until(() -> !fixture.stderrTail().isEmpty());
            Files.writeString(fixture.control("fail-objects"), "fail");
            var failed = fixture.stage(7, 19, WorldStreamObjectsFileTest.small());
            assertSame(WorldStreamTransport.ExteriorObjects.FAILED, failed.exteriorObjects());
            // STAGED objects state 2 logs the renderer's redacted reason with the first failure.
            String record = fixture.session.objectsDiagnostic().get(5, TimeUnit.SECONDS);
            assertTrue(
                    record.startsWith(
                            "World renderer could not draw exterior objects (1 tile this"
                                    + " session)"),
                    record);
            assertTrue(record.contains("synthetic shader failure at <path>"), record);
            assertTrue(record.contains("(world-wmo/spirv, program)"), record);
            assertFalse(record.contains("tester"), record);
            // Terrain commits and keeps streaming after the failed inline objects.
            fixture.commit(drawn, failed);
            Files.delete(fixture.control("fail-objects"));
            var next = fixture.stage(7, 20);
            fixture.commit(drawn, failed, next);
            assertFalse(fixture.session.completion().isDone());
        }
    }

    @Test
    void closeWithHeldObjectsDrainsCleanlyAndDeletesTheFile() throws Exception {
        var fixture = new Fixture(directory);
        try {
            var first = fixture.stage(7, 19);
            fixture.commit(first);
            fixture.hold();
            var held = fixture.session.attachObjects(first, WorldStreamObjectsFileTest.small());
            fixture.until(() -> Files.exists(fixture.control("objects-held")));
            fixture.session.closeAsync().get(10, TimeUnit.SECONDS);
            assertCanceled(held);
            fixture.session.completion().get(5, TimeUnit.SECONDS);
            assertFalse(Files.exists(fixture.objectsFile(first)));
            assertCanceled(
                    fixture.session.attachObjects(first, WorldStreamObjectsFileTest.small()));
        } finally {
            fixture.close();
        }
    }

    @Test
    void parentWriteFailureIsRecordedAndASecondAttemptIsRefused() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var first = fixture.stage(7, 19);
            fixture.commit(first);
            // A stale file at the target name makes the CREATE_NEW publication fail in the parent.
            Files.writeString(fixture.objectsFile(first), "stale");
            assertSame(
                    WorldStreamTransport.ObjectsOutcome.FAILED,
                    fixture.session
                            .attachObjects(first, WorldStreamObjectsFileTest.small())
                            .get(5, TimeUnit.SECONDS));
            assertSame(WorldStreamTransport.ExteriorObjects.FAILED, first.exteriorObjects());
            assertFalse(fixture.commands().contains("OBJECTS"));
            // The parent never owned the stale file; remove it so the session sweep stays clean.
            assertTrue(Files.readString(fixture.objectsFile(first)).equals("stale"));
            Files.delete(fixture.objectsFile(first));
            // The failure spent the resident's one request, like any other outcome.
            var misuse =
                    assertThrows(
                            ExecutionException.class,
                            () ->
                                    fixture.session
                                            .attachObjects(
                                                    first, WorldStreamObjectsFileTest.small())
                                            .get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, misuse.getCause());
            assertSame(WorldStreamTransport.ExteriorObjects.FAILED, first.exteriorObjects());
            // Terrain and the objects lane both carry on.
            var second = fixture.stage(7, 20);
            fixture.commit(first, second);
            assertSame(
                    WorldStreamTransport.ObjectsOutcome.ATTACHED,
                    fixture.session
                            .attachObjects(second, WorldStreamObjectsFileTest.small())
                            .get(5, TimeUnit.SECONDS));
            assertFalse(fixture.session.completion().isDone());
        }
    }

    @Test
    void stopBetweenTheCheckAndTheEnqueueSendsNothingAndWithdrawsTheTarget() throws Exception {
        try (var fixture = new Fixture(directory)) {
            var first = fixture.stage(7, 19);
            fixture.commit(first);
            var probed = new java.util.concurrent.atomic.AtomicBoolean();
            // The owner begins stopping after the worker's stop check, before its enqueue.
            fixture.session.beforeObjectsEnqueue =
                    handle -> {
                        probed.set(true);
                        handle.closeAsync();
                    };
            var attaching =
                    fixture.session.attachObjects(first, WorldStreamObjectsFileTest.small());
            assertCanceled(attaching);
            assertTrue(probed.get());
            assertFalse(fixture.commands().contains("OBJECTS"));
            assertFalse(Files.exists(fixture.objectsFile(first)));
            assertFalse(fixture.state().lateObjectsPending(), "Refused command is withdrawn");
            assertSame(WorldStreamTransport.ExteriorObjects.NONE, first.exteriorObjects());
            assertCanceled(
                    fixture.session.attachObjects(first, WorldStreamObjectsFileTest.small()));
        }
    }

    /** A clean CancellationException outcome, reported directly or as the cause. */
    private static void assertCanceled(java.util.concurrent.Future<?> future) {
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("Not canceled");
        } catch (CancellationException canceled) {
            // Reported directly by a future completed with it.
        } catch (ExecutionException wrapped) {
            assertInstanceOf(CancellationException.class, wrapped.getCause());
        } catch (Exception other) {
            throw new AssertionError(other);
        }
    }

    /** The actual parent transport over the scripted child below. */
    private static final class Fixture implements AutoCloseable {
        final Path root;
        final WorldStreamTransport.Session session;
        volatile Path scratch;

        Fixture(Path root) throws Exception {
            this.root = root;
            var prepared =
                    WorldStreamTransport.prepare(
                            root,
                            new WorldStreamTransport.Spec(
                                    "ab".repeat(32), 1, WorldStreamTransport.Backend.VULKAN, true));
            session =
                    prepared.launch(
                            arguments -> {
                                scratch = Path.of(arguments.get(1));
                                return new ProcessBuilder(
                                                Path.of(
                                                                System.getProperty("java.home"),
                                                                "bin",
                                                                "java")
                                                        .toString(),
                                                "-cp",
                                                System.getProperty("java.class.path"),
                                                Child.class.getName(),
                                                arguments.get(1),
                                                arguments.get(3))
                                        .start();
                            },
                            WorldStreamTestChildren.STARTUP_GUARD_MILLIS,
                            5000);
            session.initialized()
                    .get(WorldStreamTestChildren.STARTUP_GUARD_MILLIS, TimeUnit.MILLISECONDS);
        }

        Path control(String name) {
            return root.resolve(name);
        }

        void hold() throws Exception {
            Files.writeString(control("hold-objects"), "hold");
        }

        void release() throws Exception {
            Files.delete(control("hold-objects"));
        }

        Path objectsFile(WorldStreamTransport.Resident resident) {
            try {
                var field = WorldStreamTransport.Resident.class.getDeclaredField("metadata");
                field.setAccessible(true);
                var metadata = (WorldStreamParentState.Resident) field.get(resident);
                return scratch.resolve(WorldStreamObjectsFile.basename(metadata.transaction));
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        }

        HelperStderrTail stderrTail() {
            try {
                var field = WorldStreamTransport.Session.class.getDeclaredField("stderrTail");
                field.setAccessible(true);
                return (HelperStderrTail) field.get(session);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        }

        WorldStreamParentState state() {
            try {
                var field = WorldStreamTransport.Session.class.getDeclaredField("state");
                field.setAccessible(true);
                return (WorldStreamParentState) field.get(session);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        }

        String commands() throws Exception {
            var log = control("commands.log");
            return Files.exists(log) ? Files.readString(log) : "";
        }

        void until(BooleanSupplier condition) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!condition.getAsBoolean()) {
                if (System.nanoTime() > until) {
                    throw new AssertionError("Timed out");
                }
                Thread.sleep(2);
            }
        }

        WorldStreamTransport.Resident stage(int x, int y) throws Exception {
            return stage(x, y, null);
        }

        /**
         * Stages one tile; with {@code exterior}, its objects file is published first and named
         * inline in the tile header, as the exterior tile writer does.
         */
        WorldStreamTransport.Resident stage(int x, int y, WorldStreamTileWriter.Exterior exterior)
                throws Exception {
            WorldStreamTransport.TileReservation ticket = null;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (ticket == null) {
                ticket = session.tryReserveTile(x, y).orElse(null);
                if (ticket == null) {
                    if (System.nanoTime() > until) {
                        throw new AssertionError("No tile credit");
                    }
                    Thread.sleep(2);
                }
            }
            return ticket.produce(
                            (destination, stopping) -> {
                                var c = destination.context();
                                Path objectsPath = null;
                                WorldStreamObjectsFile.Reference reference = null;
                                if (exterior != null) {
                                    // The packet adopts the published file; closing the handle
                                    // here would delete it.
                                    var objects =
                                            WorldStreamTileWriter.writeObjectsUnadvertised(
                                                    destination, exterior, stopping);
                                    objectsPath =
                                            c.path()
                                                    .resolveSibling(
                                                            WorldStreamObjectsFile.basename(
                                                                    c.transaction()));
                                    reference = reference(objects.bytes(), objectsPath);
                                }
                                var tile = WorldStreamTileFileTest.full(x, y, false);
                                var h = WorldStreamTileFileTest.header(tile, false);
                                var header =
                                        new WorldStreamTileFile.Header(
                                                c.nonce(),
                                                c.scope(),
                                                c.transaction(),
                                                c.lease(),
                                                x,
                                                y,
                                                h.chunks(),
                                                h.vertices(),
                                                h.indices(),
                                                h.geometrySha256(),
                                                h.materialDigest(),
                                                h.outcome(),
                                                h.reason(),
                                                h.coverage(),
                                                h.maxX(),
                                                h.maxY(),
                                                h.uvError(),
                                                h.wdt(),
                                                h.root(),
                                                h.tex0(),
                                                h.diffuse(),
                                                reference);
                                var expected =
                                        WorldStreamTileFile.write(
                                                c.path().getParent(), header, tile, null, stopping);
                                try (var original =
                                        WorldStreamPacketFile.finalized(
                                                expected, c.path(), c.identity(), objectsPath)) {
                                    return destination.adopt(original.move(), header);
                                }
                            })
                    .get(10, TimeUnit.SECONDS);
        }

        private static WorldStreamObjectsFile.Reference reference(int length, Path file)
                throws Exception {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return new WorldStreamObjectsFile.Reference(
                    length, HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file))));
        }

        void commit(WorldStreamTransport.Resident... residents) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (true) {
                try {
                    session.commit(1, residents).get(10, TimeUnit.SECONDS);
                    return;
                } catch (ExecutionException retry) {
                    if (!(retry.getCause() instanceof WorldStreamTransport.PreparationRefusal)
                            || System.nanoTime() > until) {
                        throw retry;
                    }
                    Thread.sleep(2);
                }
            }
        }

        @Override
        public void close() throws Exception {
            Files.deleteIfExists(control("hold-objects"));
            session.closeAsync();
            session.terminatedAndDrained().get(15, TimeUnit.SECONDS);
        }
    }

    /**
     * Scripted child: stages every offered tile (inline objects drawn, or failed under
     * fail-objects), applies commits, and answers OBJECTS on its own thread after reading the
     * actual objects file for the resident tile. ATTACHED only while that exact lease is still
     * committed; DROPPED otherwise.
     */
    public static final class Child {
        private static PrintStream out;
        private static String prefix;

        private Child() {}

        public static void main(String[] args) throws Exception {
            Path session = Path.of(args[0]);
            Path control = session.getParent();
            var descriptor =
                    WorldStreamEnvelope.decodeSession(
                            Files.readAllBytes(session.resolve("session.wss")), args[1]);
            var nonce = descriptor.nonce();
            out = new PrintStream(System.out, false, StandardCharsets.US_ASCII);
            prefix =
                    "WS2 "
                            + HexFormat.of().toHexDigits(nonce.high())
                            + HexFormat.of().toHexDigits(nonce.low())
                            + " ";
            Set<WorldStreamState.Lease> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
            Map<Integer, int[]> tiles = new HashMap<>();
            Map<Integer, Long> transactions = new HashMap<>();
            long[] generations = new long[WorldStreamState.SLOTS];
            long serial = 0;
            // The renderer's object pass reports why it is unavailable on stderr only.
            System.err.println(
                    BgfxWorldStreamRenderer.objectsReason(
                            "pass unavailable",
                            "world-wmo/spirv",
                            "program",
                            new java.io.IOException(
                                    "synthetic shader failure at"
                                            + " /home/tester/vimi/world-wmo/vs.bin")));
            System.err.flush();
            send("SESSION TERRAIN_EXTERIOR_V1");
            var lease = new WorldStreamState.Lease(0, ++generations[0], ++serial);
            send(credit(lease));
            WorldStreamState.Lease staged = null;
            WorldStreamControl.Command command;
            while ((command = WorldStreamControl.read(System.in)) != null) {
                Files.writeString(
                        control.resolve("commands.log"),
                        command.getClass().getSimpleName().equals("LateObjects")
                                ? "OBJECTS\n"
                                : command.getClass().getSimpleName() + "\n",
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                if (command instanceof WorldStreamControl.Offer offer) {
                    try (var payload =
                            WorldStreamTileFile.read(
                                    session.resolve("tile-" + offer.transaction() + ".wst"),
                                    new WorldStreamTileFile.Expected(
                                            nonce,
                                            descriptor.scope(),
                                            offer.transaction(),
                                            offer.lease(),
                                            offer.length(),
                                            offer.sha256()),
                                    () -> false)) {
                        var h = payload.header();
                        synchronized (tiles) {
                            tiles.put(offer.lease().slot(), new int[] {h.x(), h.y()});
                            transactions.put(offer.lease().slot(), offer.transaction());
                        }
                        send("INPUT_CONSUMED " + offer.transaction() + " TILE");
                        // Inline objects are drawn (1), or failed (2) under fail-objects, as
                        // when the renderer's object pass is unavailable; terrain is kept.
                        int objects =
                                h.objects() == null
                                        ? 0
                                        : Files.exists(control.resolve("fail-objects")) ? 2 : 1;
                        send(
                                "STAGED "
                                        + offer.transaction()
                                        + " "
                                        + offer.lease().serial()
                                        + " "
                                        + offer.lease().slot()
                                        + " "
                                        + offer.lease().generation()
                                        + " 2 0 "
                                        + objects);
                        staged = offer.lease();
                    }
                } else if (command instanceof WorldStreamControl.Commit next) {
                    var commit =
                            WorldStreamEnvelope.decodeCommit(
                                    Files.readAllBytes(
                                            session.resolve(
                                                    "commit-" + next.transaction() + ".wsc")),
                                    next.sha256(),
                                    nonce);
                    var table = new HashSet<WorldStreamState.Lease>();
                    boolean includesStaged = false;
                    for (var member : commit.members()) {
                        for (var known : active) {
                            if (known.slot() == member.slot()
                                    && known.generation() == member.generation()) {
                                table.add(known);
                            }
                        }
                        if (staged != null
                                && staged.slot() == member.slot()
                                && staged.generation() == member.generation()) {
                            table.add(staged);
                            includesStaged = true;
                        }
                    }
                    active.retainAll(table);
                    active.addAll(table);
                    send("INPUT_CONSUMED " + next.transaction() + " COMMIT");
                    send(
                            "COMMITTED "
                                    + next.transaction()
                                    + " "
                                    + commit.expected()
                                    + " "
                                    + commit.next());
                    if (includesStaged) {
                        staged = null;
                        int free = 0;
                        while (true) {
                            int slot = free;
                            if (active.stream().noneMatch(member -> member.slot() == slot)) {
                                break;
                            }
                            free++;
                        }
                        lease = new WorldStreamState.Lease(free, ++generations[free], ++serial);
                        send(credit(lease));
                    }
                } else if (command instanceof WorldStreamControl.LateObjects late) {
                    int[] tile;
                    long tx;
                    synchronized (tiles) {
                        tile = tiles.get(late.lease().slot());
                        tx = transactions.getOrDefault(late.lease().slot(), 0L);
                    }
                    Thread.ofPlatform()
                            .daemon()
                            .start(
                                    () ->
                                            objects(
                                                    session,
                                                    control,
                                                    descriptor,
                                                    late,
                                                    tile,
                                                    tx,
                                                    active));
                } else if (command instanceof WorldStreamControl.Close) {
                    send("CLOSED");
                    return;
                }
            }
        }

        private static void objects(
                Path session,
                Path control,
                WorldStreamEnvelope.Session descriptor,
                WorldStreamControl.LateObjects late,
                int[] tile,
                long tx,
                Set<WorldStreamState.Lease> active) {
            try {
                var hold = control.resolve("hold-objects");
                if (Files.exists(hold)) {
                    Files.writeString(control.resolve("objects-held"), "held");
                    while (Files.exists(hold)) {
                        Thread.sleep(2);
                    }
                    Files.delete(control.resolve("objects-held"));
                }
                String outcome;
                if (Files.exists(control.resolve("fail-objects"))) {
                    outcome = "FAILED";
                } else if (!active.contains(late.lease()) || tx != late.transaction()) {
                    outcome = "DROPPED";
                } else {
                    var data =
                            WorldStreamObjectsFile.read(
                                    session.resolve(
                                            WorldStreamObjectsFile.basename(late.transaction())),
                                    new WorldStreamObjectsFile.Identity(
                                            descriptor.nonce(),
                                            descriptor.scope(),
                                            late.transaction(),
                                            late.lease(),
                                            tile[0],
                                            tile[1]),
                                    new WorldStreamObjectsFile.Reference(
                                            late.length(), late.sha256()),
                                    () -> false);
                    outcome = data.placementCount() == 2 ? "ATTACHED" : "FAILED";
                }
                send(
                        "OBJECTS_DONE "
                                + late.transaction()
                                + " "
                                + late.lease().serial()
                                + " "
                                + late.lease().slot()
                                + " "
                                + late.lease().generation()
                                + " "
                                + outcome);
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        }

        private static String credit(WorldStreamState.Lease lease) {
            return "CREDIT " + lease.serial() + " " + lease.slot() + " " + lease.generation();
        }

        private static synchronized void send(String text) {
            out.print(prefix + text + "\n");
            out.flush();
        }
    }
}
