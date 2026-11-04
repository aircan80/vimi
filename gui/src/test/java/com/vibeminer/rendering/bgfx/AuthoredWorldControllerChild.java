package com.vibeminer.rendering.bgfx;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/** Actual packet decoder and mapped-frame codec; authored pixels, no GPU/rendering claim. */
public final class AuthoredWorldControllerChild {
    private AuthoredWorldControllerChild() {}

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        var descriptor =
                WorldStreamEnvelope.decodeSession(
                        Files.readAllBytes(directory.resolve("session.wss")), args[1]);
        var nonce = descriptor.nonce();
        String prefix =
                "WS2 "
                        + HexFormat.of().toHexDigits(nonce.high())
                        + HexFormat.of().toHexDigits(nonce.low())
                        + " ";
        try (var frames =
                WorldFrameFileV2.open(directory.resolve("frames.wsf"), nonce, descriptor.scope())) {
            var state = new State(prefix);
            state.send("SESSION TERRAIN_BASIC_V1");
            state.grant();
            WorldStreamControl.Camera camera = null;
            long content = 0, sequence = 0, policy = 0;
            WorldStreamControl.Command command;
            while ((command = WorldStreamControl.read(System.in)) != null) {
                Files.writeString(
                        directory.getParent().resolve("authored-commands.log"),
                        command.toString() + "\n",
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                if (!command.nonce().equals(nonce)) throw new AssertionError("Wrong nonce");
                if (command instanceof WorldStreamControl.Camera next) {
                    camera = next;
                    if (policy != camera.policy()) {
                        policy = camera.policy();
                        state.send(
                                "CAMERA_APPLIED "
                                        + policy
                                        + " "
                                        + camera.width()
                                        + " "
                                        + camera.height());
                    }
                } else if (command instanceof WorldStreamControl.Offer offer) {
                    if (!state.available || !offer.lease().equals(state.lease))
                        throw new AssertionError("Wrong input lease");
                    state.available=false;
                    try (var payload =
                            WorldStreamTileFile.read(
                                    directory.resolve("tile-" + offer.transaction() + ".wst"),
                                    new WorldStreamTileFile.Expected(
                                            nonce,
                                            descriptor.scope(),
                                            offer.transaction(),
                                            offer.lease(),
                                            offer.length(),
                                            offer.sha256()),
                                    () -> false)) {
                        var h = payload.header();
                        int covered = 0;
                        for (long word : h.coverage()) covered += Long.bitCount(word);
                        state.send("INPUT_CONSUMED " + offer.transaction() + " TILE");
                        state.send(
                                "STAGED "
                                        + offer.transaction()
                                        + " "
                                        + offer.lease().serial()
                                        + " "
                                        + offer.lease().slot()
                                        + " "
                                        + offer.lease().generation()
                                        + " "
                                        + (h.outcome() == WorldStreamTileFile.Outcome.TEXTURED
                                                ? 1
                                                : 2)
                                        + " "
                                        + covered);
                    }
                } else if (command instanceof WorldStreamControl.Commit next) {
                    var commit =
                            WorldStreamEnvelope.decodeCommit(
                                    Files.readAllBytes(
                                            directory.resolve(
                                                    "commit-" + next.transaction() + ".wsc")),
                                    next.sha256(),
                                    nonce);
                    state.members.clear();
                    for (var member : commit.members()) state.members.add(member.slot());
                    state.send("INPUT_CONSUMED " + next.transaction() + " COMMIT");
                    state.send(
                            "COMMITTED "
                                    + next.transaction()
                                    + " "
                                    + commit.expected()
                                    + " "
                                    + commit.next());
                    Files.writeString(
                            directory.getParent().resolve("authored-commits.log"),
                            commit.next() + " " + commit.members().size() + "\n",
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                    content = commit.next();
                    if(!state.available) state.grant();
                } else if (command instanceof WorldStreamControl.CancelUnused cancel) {
                    state.canceled(cancel.transaction(), cancel.lease());
                } else if (command instanceof WorldStreamControl.Cancel cancel) {
                    state.canceled(cancel.transaction(), state.lease);
                } else if (command instanceof WorldStreamControl.Close) {
                    state.send("CLOSED");
                    return;
                }
                if (camera != null && content > 0) {
                    var pixels =
                            ByteBuffer.allocate(camera.width() * camera.height() * 4)
                                    .order(ByteOrder.LITTLE_ENDIAN);
                    while (pixels.hasRemaining()) pixels.putInt(0xff008000 | (int) content);
                    pixels.flip();
                    frames.write(
                            new WorldStreamFrameGate.Frame(
                                    nonce,
                                    ++sequence,
                                    camera.revision(),
                                    content,
                                    camera.policy(),
                                    camera.width(),
                                    camera.height()),
                            pixels);
                }
            }
        }
    }

    private static final class State {
        final String prefix;
        final Set<Integer> members = new HashSet<>();
        final long[] generations = new long[10];
        long serial;
        boolean available;
        WorldStreamState.Lease lease;

        State(String prefix) {
            this.prefix = prefix;
        }

        void send(String text) {
            System.out.print(prefix + text + "\n");
            System.out.flush();
        }

        void grant() {
            int slot = 0;
            while (members.contains(slot)) slot++;
            available=true;
            lease = new WorldStreamState.Lease(slot, ++generations[slot], ++serial);
            send("CREDIT " + lease.serial() + " " + lease.slot() + " " + lease.generation());
        }

        void canceled(long transaction, WorldStreamState.Lease canceled) {
            available=false;
            send(
                    "CANCELED "
                            + transaction
                            + " "
                            + canceled.serial()
                            + " "
                            + canceled.slot()
                            + " "
                            + canceled.generation());
            grant();
        }
    }
}
