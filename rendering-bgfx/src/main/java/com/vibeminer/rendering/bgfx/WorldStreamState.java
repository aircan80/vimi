package com.vibeminer.rendering.bgfx;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Pure owner-thread metadata model for the proposed World v2 mode; no renderer is wired to it. */
final class WorldStreamState {
    static final int SLOTS = 10, MAX_ACTIVE = 9, MAX_REFERENCES = 48;

    record Nonce(long high, long low) {
        Nonce {
            if ((high | low) == 0) {
                throw new IllegalArgumentException("Zero session nonce");
            }
        }
    }

    record Lease(int slot, long generation, long serial) {
        Lease {
            if (slot < 0 || slot >= SLOTS || generation <= 0 || serial <= 0) {
                throw new IllegalArgumentException("Invalid slot lease");
            }
        }
    }

    enum Phase {
        CREDIT,
        READING,
        UPLOADING,
        STAGED,
        COMMITTED_DRAIN,
        CANCEL_DRAIN,
        CLOSED
    }

    enum Result {
        OK,
        WRONG_SESSION,
        STALE,
        BAD_LEASE,
        BUSY,
        INVALID_TABLE,
        REVISION_MISMATCH,
        CLOSED
    }

    private final Nonce nonce;
    private final long[] generations = new long[SLOTS];
    private final int[] coordinates = new int[SLOTS];
    // Late objects lane: the tile transaction of each slot's current generation, whether that
    // generation already holds objects, and the one in-flight OBJECTS target.
    private final long[] transactions = new long[SLOTS];
    private final boolean[] objects = new boolean[SLOTS];
    private Lease objectsLease;
    private List<Lease> active = List.of();
    private Lease incoming;
    private Phase phase;
    private long serial, highWater, offerTransaction, contentRevision, reservedCommit;
    private boolean decoderOwned, inputConsumed, stopping;
    private int references;

    WorldStreamState(Nonce nonce) {
        this.nonce = Objects.requireNonNull(nonce);
        java.util.Arrays.fill(coordinates, -1);
        grant();
    }

    Lease credit() {
        return phase == Phase.CREDIT && !stopping ? incoming : null;
    }

    Phase phase() {
        return phase;
    }

    List<Lease> active() {
        return active;
    }

    long contentRevision() {
        return contentRevision;
    }

    long highWater() {
        return highWater;
    }

    int references() {
        return references;
    }

    boolean inputConsumed() {
        return inputConsumed;
    }

    boolean drained() {
        return phase == Phase.CLOSED;
    }

    Result offer(Nonce session, long tx, Lease lease) {
        return offer(session, tx, lease, -1, -1, false);
    }

    Result offer(Nonce session, long tx, Lease lease, int tileX, int tileY) {
        return offer(session, tx, lease, tileX, tileY, true);
    }

    private Result offer(Nonce session, long tx, Lease lease, int tileX, int tileY, boolean known) {
        var result = newTransaction(session, tx);
        if (result != Result.OK) {
            return result;
        }
        if (phase != Phase.CREDIT) {
            return Result.BUSY;
        }
        if (!Objects.equals(incoming, lease)) {
            return Result.BAD_LEASE;
        }
        if (known && (tileX < 0 || tileX > 63 || tileY < 0 || tileY > 63)) {
            return Result.INVALID_TABLE;
        }
        offerTransaction = tx;
        transactions[lease.slot()] = tx;
        coordinates[lease.slot()] = known ? tileY * 64 + tileX : -1;
        decoderOwned = true;
        inputConsumed = false;
        phase = Phase.READING;
        return Result.OK;
    }

    Result decoded(Lease lease, boolean valid, int tileX, int tileY) {
        if (!Objects.equals(incoming, lease) || !decoderOwned) {
            return Result.BAD_LEASE;
        }
        if (valid) {
            if (tileX < 0
                    || tileX > 63
                    || tileY < 0
                    || tileY > 63
                    || coordinates[lease.slot()] != -1
                            && coordinates[lease.slot()] != tileY * 64 + tileX) {
                return Result.INVALID_TABLE;
            }
            coordinates[lease.slot()] = tileY * 64 + tileX;
        }
        return decoded(lease, valid);
    }

    // Invoked only after decoder closes the actual input and drops all raw/output aliases.
    Result decoded(Lease lease, boolean valid) {
        if (!Objects.equals(incoming, lease) || !decoderOwned) {
            return Result.BAD_LEASE;
        }
        if (valid && coordinates[lease.slot()] == -1) {
            return Result.INVALID_TABLE;
        }
        decoderOwned = false;
        inputConsumed = true;
        if (stopping || phase == Phase.CANCEL_DRAIN || !valid) {
            phase = Phase.CANCEL_DRAIN;
            retireIfDrained();
        } else {
            phase = Phase.UPLOADING;
        }
        return Result.OK;
    }

    Result submittedReference(Lease lease) {
        if (stopping) {
            return Result.CLOSED;
        }
        if (!Objects.equals(incoming, lease)) {
            return Result.BAD_LEASE;
        }
        if (phase != Phase.UPLOADING || references == MAX_REFERENCES) {
            return Result.BUSY;
        }
        references++;
        return Result.OK;
    }

    // Owner consumes bounded callback credits; callbacks themselves never mutate this model.
    Result consumedReference(Lease lease) {
        if (!Objects.equals(incoming, lease) || references == 0) {
            return Result.BAD_LEASE;
        }
        references--;
        retireIfDrained();
        return Result.OK;
    }

    Result staged(Lease lease) {
        if (stopping) {
            return Result.CLOSED;
        }
        if (!Objects.equals(incoming, lease)) {
            return Result.BAD_LEASE;
        }
        if (phase != Phase.UPLOADING) {
            return Result.BUSY;
        }
        phase = Phase.STAGED;
        return Result.OK;
    }

    Result reserveCommit(Nonce session, long tx) {
        var result = newTransaction(session, tx);
        if (result != Result.OK) {
            return result;
        }
        if (reservedCommit != 0) {
            return Result.BUSY;
        }
        reservedCommit = tx;
        return Result.OK;
    }

    Result rejectReserved(Nonce session, long tx) {
        if (!nonce.equals(session)) {
            return Result.WRONG_SESSION;
        }
        if (reservedCommit == 0 || reservedCommit != tx) {
            return Result.STALE;
        }
        reservedCommit = 0;
        retireIfDrained();
        return Result.OK;
    }

    Result commit(Nonce session, long tx, long expected, long next, List<Lease> table) {
        var result = reserveCommit(session, tx);
        return result == Result.OK ? commitReserved(session, tx, expected, next, table) : result;
    }

    Result commitReserved(Nonce session, long tx, long expected, long next, List<Lease> table) {
        if (!nonce.equals(session)) {
            return Result.WRONG_SESSION;
        }
        if (reservedCommit == 0 || reservedCommit != tx) {
            return Result.STALE;
        }
        reservedCommit = 0;
        if (stopping) {
            retireIfDrained();
            return Result.CLOSED;
        }
        if (expected != contentRevision || expected == Long.MAX_VALUE || next != expected + 1) {
            return Result.REVISION_MISMATCH;
        }
        if (table == null || table.size() > MAX_ACTIVE) {
            return Result.INVALID_TABLE;
        }
        var seen = new HashSet<Lease>();
        var seenCoordinates = new HashSet<Integer>();
        boolean includesIncoming = false;
        for (var member : table) {
            if (member == null || !seen.add(member)) {
                return Result.INVALID_TABLE;
            }
            boolean staged = phase == Phase.STAGED && member.equals(incoming);
            if (!active.contains(member) && !staged) {
                return Result.INVALID_TABLE;
            }
            if (!seenCoordinates.add(coordinates[member.slot()])) {
                return Result.INVALID_TABLE;
            }
            includesIncoming |= staged;
        }
        // Retained order is explicitly the caller's table, never slot index or arrival order.
        active = List.copyOf(table);
        contentRevision = next;
        if (includesIncoming) {
            phase = Phase.COMMITTED_DRAIN;
            retireIfDrained();
        }
        return Result.OK;
    }

    Result cancel(Nonce session, long establishedOffer, Lease lease) {
        var result = session(session);
        if (result != Result.OK) {
            return result;
        }
        if (!Objects.equals(incoming, lease)
                || establishedOffer != offerTransaction
                || establishedOffer <= 0) {
            return Result.STALE;
        }
        if (phase == Phase.CANCEL_DRAIN) {
            return Result.OK;
        }
        if (phase != Phase.READING && phase != Phase.UPLOADING && phase != Phase.STAGED) {
            return Result.STALE;
        }
        phase = Phase.CANCEL_DRAIN;
        retireIfDrained();
        return Result.OK;
    }

    // Parent contract: its unpublished writer has drained before this command is sent.
    Result cancelUnused(Nonce session, long tx, Lease lease) {
        var result = newTransaction(session, tx);
        if (result != Result.OK) {
            return result;
        }
        if (phase != Phase.CREDIT || !Objects.equals(incoming, lease)) {
            return Result.BAD_LEASE;
        }
        phase = Phase.CANCEL_DRAIN;
        retireIfDrained();
        return Result.OK;
    }

    /**
     * Admits one late objects command. OK only for the exact active lease that tile transaction
     * staged, once per generation; STALE means the parent's resident is no longer drawn here and
     * the command is answered DROPPED without reading its file. BUSY is a credit violation.
     */
    Result objectsOffer(Nonce session, long tx, Lease lease) {
        var result = session(session);
        if (result != Result.OK) {
            return result;
        }
        if (objectsLease != null) {
            return Result.BUSY;
        }
        if (lease == null || !objectsTarget(tx, lease)) {
            return Result.STALE;
        }
        objectsLease = lease;
        return Result.OK;
    }

    /** Whether the in-flight objects may still attach: its generation is active and empty. */
    boolean objectsLive(Lease lease) {
        return !stopping
                && Objects.equals(objectsLease, lease)
                && active.contains(lease)
                && !objects[lease.slot()];
    }

    /**
     * Retires the in-flight objects command. {@code attached} records that the renderer actually
     * attached them to this generation; a later generation of the slot starts empty again.
     */
    Result objectsDone(Lease lease, boolean attached) {
        if (lease == null || !Objects.equals(objectsLease, lease)) {
            return Result.BAD_LEASE;
        }
        if (attached && (!active.contains(lease) || objects[lease.slot()])) {
            return Result.INVALID_TABLE;
        }
        objectsLease = null;
        if (attached) {
            objects[lease.slot()] = true;
        }
        return Result.OK;
    }

    Lease objectsInFlight() {
        return objectsLease;
    }

    /** Recorded {@code y * 64 + x} of an active lease's tile, or -1. */
    int coordinate(Lease lease) {
        return lease != null && active.contains(lease) ? coordinates[lease.slot()] : -1;
    }

    private boolean objectsTarget(long tx, Lease lease) {
        int slot = lease.slot();
        return tx > 0
                && transactions[slot] == tx
                && generations[slot] == lease.generation()
                && active.contains(lease)
                && !objects[slot];
    }

    void close() {
        stopping = true;
        phase = Phase.CANCEL_DRAIN;
        retireIfDrained();
    }

    private Result session(Nonce session) {
        if (stopping) {
            return Result.CLOSED;
        }
        return nonce.equals(session) ? Result.OK : Result.WRONG_SESSION;
    }

    private Result newTransaction(Nonce session, long tx) {
        var result = session(session);
        if (result != Result.OK) {
            return result;
        }
        if (tx <= highWater || tx <= 0) {
            return Result.STALE;
        }
        if (tx == Long.MAX_VALUE) {
            close();
            return Result.CLOSED;
        }
        highWater = tx;
        return Result.OK;
    }

    private void retireIfDrained() {
        if (decoderOwned || references != 0 || stopping && reservedCommit != 0) {
            return;
        }
        if (stopping) {
            active = List.of();
            incoming = null;
            phase = Phase.CLOSED;
        } else if (phase == Phase.CANCEL_DRAIN || phase == Phase.COMMITTED_DRAIN) {
            incoming = null;
            grant();
        }
    }

    private void grant() {
        if (serial == Long.MAX_VALUE) {
            throw new IllegalStateException("Session credit exhausted");
        }
        int free = 0;
        while (free < SLOTS) {
            int slot = free;
            if (active.stream().noneMatch(member -> member.slot() == slot)) {
                break;
            }
            free++;
        }
        if (free == SLOTS || generations[free] == Long.MAX_VALUE) {
            throw new IllegalStateException("Session slot exhausted");
        }
        incoming = new Lease(free, ++generations[free], ++serial);
        coordinates[free] = -1;
        transactions[free] = 0;
        objects[free] = false;
        offerTransaction = 0;
        inputConsumed = false;
        phase = Phase.CREDIT;
    }
}
