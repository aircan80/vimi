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
            boolean exteriors = descriptor.exteriorObjects();
            state.send(exteriors ? "SESSION TERRAIN_EXTERIOR_V1" : "SESSION TERRAIN_BASIC_V1");
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
                        state.tileY.put(offer.lease().slot(),h.y());
                        state.tileX.put(offer.lease().slot(),h.x());
                        state.leases.put(offer.lease().slot(),offer.lease());
                        state.transactions.put(offer.lease().slot(),offer.transaction());
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
                                        + covered
                                        + (exteriors ? " 0" : ""));
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
                    var heldCommit = directory.getParent().resolve("authored-hold-commit");
                    if (Files.exists(heldCommit) && Long.parseLong(Files.readString(heldCommit)) == commit.next()) {
                        Files.writeString(directory.getParent().resolve("authored-held-commit"), Long.toString(commit.next()));
                        while (Files.exists(heldCommit)) Thread.sleep(2);
                    }
                    // Record the commit before acknowledging it, so any parent state derived from
                    // COMMITTED (and any test reading this log after observing that state) sees it.
                    Files.writeString(
                            directory.getParent().resolve("authored-commits.log"),
                            commit.next() + " " + commit.members().size() + "\n",
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                    state.send("INPUT_CONSUMED " + next.transaction() + " COMMIT");
                    state.send(
                            "COMMITTED "
                                    + next.transaction()
                                    + " "
                                    + commit.expected()
                                    + " "
                                    + commit.next());
                    content = commit.next();
                    if(!state.available) state.grant();
                } else if (command instanceof WorldStreamControl.CancelUnused cancel) {
                    var holdUnused = directory.getParent().resolve("authored-hold-unused-credit");
                    if (Files.exists(holdUnused)) {
                        Files.writeString(directory.getParent().resolve("authored-held-unused-credit"), "held");
                        while (Files.exists(holdUnused)) Thread.sleep(2);
                    }
                    state.canceled(cancel.transaction(), cancel.lease());
                } else if (command instanceof WorldStreamControl.Cancel cancel) {
                    state.canceled(cancel.transaction(), state.lease);
                } else if (command instanceof WorldStreamControl.LateObjects late) {
                    // Reads the actual objects file for its resident tile; attaches only while
                    // that exact lease is still committed. An authored control file fails it.
                    String outcome;
                    int slot = late.lease().slot();
                    if (Files.exists(directory.getParent().resolve("authored-fail-objects"))) {
                        outcome = "FAILED";
                    } else if (!state.members.contains(slot)
                            || !late.lease().equals(state.leases.get(slot))
                            || state.transactions.getOrDefault(slot, 0L) != late.transaction()) {
                        outcome = "DROPPED";
                    } else {
                        var data =
                                WorldStreamObjectsFile.read(
                                        directory.resolve(
                                                WorldStreamObjectsFile.basename(late.transaction())),
                                        new WorldStreamObjectsFile.Identity(
                                                nonce,
                                                descriptor.scope(),
                                                late.transaction(),
                                                late.lease(),
                                                state.tileX.get(slot),
                                                state.tileY.get(slot)),
                                        new WorldStreamObjectsFile.Reference(
                                                late.length(), late.sha256()),
                                        () -> false);
                        outcome = data.placementCount() > 0 ? "ATTACHED" : "FAILED";
                        if (outcome.equals("ATTACHED")) state.objects.add(slot);
                    }
                    Files.writeString(
                            directory.getParent().resolve("authored-objects.log"),
                            state.tileY.getOrDefault(slot, -1) + " " + outcome + "\n",
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                    state.send(
                            "OBJECTS_DONE "
                                    + late.transaction()
                                    + " "
                                    + late.lease().serial()
                                    + " "
                                    + slot
                                    + " "
                                    + late.lease().generation()
                                    + " "
                                    + outcome);
                } else if (command instanceof WorldStreamControl.Close) {
                    state.send("CLOSED");
                    return;
                }
                if (camera != null && content > 0) {
                    var pixels =
                            ByteBuffer.allocate(camera.width() * camera.height() * 4)
                                    .order(ByteOrder.LITTLE_ENDIAN);
                    boolean sky=Files.exists(directory.getParent().resolve("authored-sky"));
                    var until=directory.getParent().resolve("authored-sky-until-y");
                    if(Files.exists(until)) {
                        int targetY=Integer.parseInt(Files.readString(until));
                        sky=state.members.stream().noneMatch(slot->state.tileY.getOrDefault(slot,-1)==targetY);
                    }
                    for(int row=0;row<camera.height();row++) {
                        float t=(float)row/camera.height();
                        int color=sky ? 0xff000000|((int)(91+80*t)<<16)|((int)(141+60*t)<<8)|(int)(177+37*t)
                                : 0xff008000|(int)content;
                        for(int column=0;column<camera.width();column++) pixels.putInt(color);
                    }
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
        final java.util.Map<Integer,Integer> tileY=new java.util.HashMap<>();
        final java.util.Map<Integer,Integer> tileX=new java.util.HashMap<>();
        final java.util.Map<Integer,WorldStreamState.Lease> leases=new java.util.HashMap<>();
        final java.util.Map<Integer,Long> transactions=new java.util.HashMap<>();
        final Set<Integer> objects = new HashSet<>();
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
