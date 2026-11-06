package com.vibeminer.rendering.bgfx;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Fixed-size parent operation metadata. No source, file, callback, process or pixel owners. */
final class WorldStreamParentState {
    static final class Ticket {
        final Object owner;
        final WorldStreamState.Lease lease;
        final long transaction;
        final int x, y;
        boolean producerStarted, producerDrained, offered, inputConsumed, fileRetired = true;
        boolean commitResolved;
        boolean cancelRequested,
                cancelSent,
                cancelResolved,
                unusedSent,
                canceled,
                rejected,
                committed,
                credited;
        int outcome, coverage;
        boolean objectsOffered;
        Resident resident;

        private Ticket(Object owner, WorldStreamState.Lease lease, long transaction, int x, int y) {
            this.owner = owner;
            this.lease = lease;
            this.transaction = transaction;
            this.x = x;
            this.y = y;
        }
    }

    static final class Resident {
        final Object owner;
        final WorldStreamState.Lease lease;
        final long transaction;
        final int x, y, outcome, coverage;

        /** -1 outside the exterior profile; else 0 none, 1 drawn, 2 failed (terrain kept). */
        final int objects;

        // Late objects: admitted at most once, published at most once; the terminal outcome once
        // known (the child's reply, or FAILED when the parent could not write the objects file).
        boolean lateRequested, lateSent;
        volatile WorldStreamStatus.LateOutcome late;

        private Resident(Ticket ticket, int objects) {
            this.objects = objects;
            owner = ticket.owner;
            lease = ticket.lease;
            transaction = ticket.transaction;
            x = ticket.x;
            y = ticket.y;
            outcome = ticket.outcome;
            coverage = ticket.coverage;
        }
    }

    static final class Commit {
        final long transaction, expected, next, policy;
        final List<Resident> ordered;
        boolean inputConsumed, terminal, accepted, fileRetired;

        private Commit(long transaction, long expected, long policy, List<Resident> ordered) {
            this.transaction = transaction;
            this.expected = expected;
            next = expected + 1;
            this.policy = policy;
            this.ordered = List.copyOf(ordered);
        }
    }

    enum Change {
        IGNORED,
        SESSION,
        CREDIT,
        CAMERA,
        FRAME,
        INPUT_TILE,
        INPUT_COMMIT,
        STAGED,
        COMMITTED,
        REJECTED,
        CANCEL_REJECTED,
        CANCELED,
        OBJECTS,
        HORIZON,
        CLOSED
    }

    private final Object owner = new Object();
    private final WorldStreamEnvelope.Session session;
    private final long[] generations = new long[10];
    private WorldStreamState.Lease credit;
    private long serial, transaction, content;
    private boolean hello, closed, childGone;
    // The session's one HORIZON (#372): sent, then answered once.
    private boolean horizonSent, horizonAnswered;
    private Ticket ticket;
    private Commit commit;
    private List<Resident> active = List.of();
    private Resident objectsTarget;

    WorldStreamParentState(WorldStreamEnvelope.Session session) {
        this.session = Objects.requireNonNull(session);
    }

    synchronized Ticket reserve(int x, int y) {
        if (x < 0 || x > 63 || y < 0 || y > 63) {
            throw new IllegalArgumentException("Invalid tile");
        }
        if (closed
                || !hello
                || credit == null
                || ticket != null
                        && (!ticket.credited
                                || !ticket.fileRetired
                                || ticket.cancelSent && !ticket.cancelResolved)
                || commit != null && (!commit.terminal || !commit.fileRetired)) {
            return null;
        }
        ticket = new Ticket(owner, credit, nextTransaction(), x, y);
        credit = null;
        return ticket;
    }

    synchronized void beginProducer(Ticket value) {
        requireTicket(value);
        require(
                !value.producerStarted && !value.producerDrained && !value.cancelRequested,
                "Producer start");
        value.producerStarted = true;
        value.fileRetired = false; // Destination registered BEFORE any writer runs.
    }

    /** Includes the actual producer finally AND any scheduled cancellation callback finally. */
    synchronized void producerDrained(Ticket value) {
        requireIdentity(value);
        require(value.producerStarted && !value.producerDrained, "Producer drain");
        value.producerDrained = true;
    }

    synchronized WorldStreamControl.Offer offer(
            Ticket value, int length, String sha, int outcome, int coverage) {
        return offer(value, length, sha, outcome, coverage, false);
    }

    synchronized WorldStreamControl.Offer offer(
            Ticket value, int length, String sha, int outcome, int coverage, boolean objects) {
        requireTicket(value);
        require(!objects || session.exteriorObjects(), "Objects outside session profile");
        require(
                value.producerDrained
                        && !value.cancelRequested
                        && !value.offered
                        && !value.fileRetired,
                "Offer publication");
        require(
                outcome == 1 && coverage >= 1 && coverage <= 256 || outcome == 2 && coverage == 0,
                "Offer appearance");
        var command =
                new WorldStreamControl.Offer(
                        session.nonce(), value.transaction, value.lease, length, sha);
        value.outcome = outcome;
        value.coverage = coverage;
        value.objectsOffered = objects;
        value.offered = true;
        return command;
    }

    synchronized WorldStreamControl.Command cancel(Ticket value) {
        require(value != null && value.owner == owner, "Foreign tile reservation");
        if (closed || value.credited) {
            return null;
        }
        requireTicket(value);
        if (value.resident != null && commit != null && !commit.terminal) {
            return null;
        }
        if (value.credited
                || value.canceled
                || value.rejected
                || value.commitResolved
                || value.cancelSent
                || value.unusedSent) {
            return null;
        }
        value.cancelRequested = true;
        if (value.offered) {
            value.cancelSent = true;
            return new WorldStreamControl.Cancel(
                    session.nonce(),
                    value.transaction,
                    value.lease.slot(),
                    value.lease.generation());
        }
        if (!value.producerStarted) {
            value.producerDrained = true;
        }
        if (value.producerDrained && value.fileRetired) {
            value.unusedSent = true;
            return new WorldStreamControl.CancelUnused(
                    session.nonce(), value.transaction, value.lease);
        }
        return null;
    }

    synchronized void retireTileFile(Ticket value) {
        requireIdentity(value);
        require(
                !value.fileRetired
                        && (value.offered
                                ? value.inputConsumed || childGone
                                : value.producerDrained),
                "Tile input retirement");
        value.fileRetired = true;
    }

    Commit prepareCommit(long policy, List<Resident> ordered) {
        Objects.requireNonNull(ordered);
        int count = ordered.size();
        require(count >= 0 && count <= 9, "Commit count");
        var snapshot = new Resident[count];
        // Caller list code must never run under the state monitor.
        for (int i = 0; i < count; i++) {
            snapshot[i] = ordered.get(i);
        }
        return prepareCapturedCommit(policy, snapshot);
    }

    private synchronized Commit prepareCapturedCommit(long policy, Resident[] snapshot) {
        if (closed
                || !hello
                || commit != null && (!commit.terminal || !commit.fileRetired)
                || ticket != null && !ticket.offered && !ticket.unusedSent && !ticket.credited) {
            return null;
        }
        require(policy >= session.policy() && content < Long.MAX_VALUE, "Commit bounds");
        var stable = java.util.Arrays.asList(snapshot);
        var slots = new HashSet<Integer>();
        var coordinates = new HashSet<Integer>();
        for (var member : stable) {
            require(
                    member != null
                            && member.owner == owner
                            && generations[member.lease.slot()] == member.lease.generation()
                            && (active.contains(member)
                                    || ticket != null
                                            && ticket.resident == member
                                            && !ticket.commitResolved
                                            && !ticket.cancelRequested
                                            && !ticket.canceled
                                            && !ticket.rejected
                                            && !ticket.credited)
                            && slots.add(member.lease.slot())
                            && coordinates.add(member.y * 64 + member.x),
                    "Commit membership");
        }
        commit = new Commit(nextTransaction(), content, policy, stable);
        return commit;
    }

    /**
     * The session's one horizon command, or null once one was sent or the session ended. It sits
     * outside the tile transaction sequence.
     */
    synchronized WorldStreamControl.Horizon horizon(int length, String sha) {
        if (closed || !hello || horizonSent) {
            return null;
        }
        horizonSent = true;
        return new WorldStreamControl.Horizon(session.nonce(), length, sha);
    }

    /** A horizon command that was built but not sent (the session began stopping). */
    synchronized void horizonUnsent() {
        horizonAnswered = true;
    }

    /**
     * Publishes the one late objects command for a resident admitted by {@link #admitLateObjects}.
     * Returns null when the resident is no longer the active generation of its slot (the caller
     * then reports it dropped without sending anything).
     */
    synchronized WorldStreamControl.LateObjects lateObjects(
            Resident member, int length, String sha) {
        require(session.exteriorObjects(), "Late objects outside session profile");
        require(!closed && hello, "Late objects after session end");
        require(member != null && member.owner == owner, "Foreign late objects resident");
        require(objectsTarget == null, "Late objects credit");
        require(
                member.objects == 0
                        && member.lateRequested
                        && !member.lateSent
                        && member.late == null,
                "Unadmitted or duplicate resident objects");
        member.lateSent = true;
        if (generations[member.lease.slot()] != member.lease.generation()
                || !active.contains(member)) {
            member.late = WorldStreamStatus.LateOutcome.DROPPED;
            return null;
        }
        var command =
                new WorldStreamControl.LateObjects(
                        session.nonce(), member.transaction, member.lease, length, sha);
        objectsTarget = member;
        return command;
    }

    /**
     * Synchronous admission before any file is written. Misuse (a foreign resident, one with inline
     * objects, or one already requested) is refused with IllegalArgumentException so the caller can
     * fail only that request. Returns false when the resident is no longer the active generation of
     * its slot. Any admission of a live session consumes the resident's one request, whatever the
     * delivery then does.
     */
    synchronized boolean admitLateObjects(Resident member) {
        if (member == null || member.owner != owner) {
            throw new IllegalArgumentException("Foreign late objects resident");
        }
        if (member.objects != 0 || member.lateRequested) {
            throw new IllegalArgumentException("Resident objects already requested or inline");
        }
        if (closed || !hello) {
            return false;
        }
        if (generations[member.lease.slot()] != member.lease.generation()
                || !active.contains(member)) {
            member.lateRequested = true;
            member.late = WorldStreamStatus.LateOutcome.DROPPED;
            return false;
        }
        member.lateRequested = true;
        return true;
    }

    /**
     * Records the terminal outcome of an admitted resident whose objects file the parent could not
     * write; nothing was sent for it. A resident retired since admission is DROPPED, like every
     * other retired path; otherwise FAILED. Returns the recorded outcome.
     */
    synchronized WorldStreamStatus.LateOutcome lateObjectsFailed(Resident member) {
        require(
                member != null
                        && member.owner == owner
                        && member.lateRequested
                        && !member.lateSent
                        && member.late == null,
                "Late objects failure without admission");
        member.late =
                generations[member.lease.slot()] != member.lease.generation()
                                || !active.contains(member)
                        ? WorldStreamStatus.LateOutcome.DROPPED
                        : WorldStreamStatus.LateOutcome.FAILED;
        return member.late;
    }

    /**
     * Withdraws a published command that the transport refused to send because the session began
     * stopping; no reply can arrive for it, so its credit is returned and nothing is recorded.
     */
    synchronized void lateObjectsUnsent(Resident member) {
        require(member != null && objectsTarget == member, "Unsent late objects target");
        objectsTarget = null;
    }

    synchronized boolean lateObjectsPending() {
        return objectsTarget != null;
    }

    synchronized void retireCommitFile(Commit value) {
        require(
                value == commit && (value.inputConsumed || childGone) && !value.fileRetired,
                "Commit file retirement");
        value.fileRetired = true;
    }

    synchronized Change status(WorldStreamStatus.Message message) {
        Objects.requireNonNull(message);
        if (!session.nonce().equals(message.nonce())) {
            return Change.IGNORED;
        }
        require(!closed, "Status after terminal");
        if (message instanceof WorldStreamStatus.Session value) {
            require(!hello, "Duplicate session");
            require(
                    value.exteriorObjects() == session.exteriorObjects(),
                    "Session profile differs");
            hello = true;
            return Change.SESSION;
        }
        require(hello, "Status before session");
        if (message instanceof WorldStreamStatus.Credit value) {
            require(
                    credit == null
                            && value.lease().serial() == serial + 1
                            && value.lease().generation() == generations[value.lease().slot()] + 1,
                    "Credit order");
            int free = 0;
            while (free < 10) {
                int slot = free;
                if (active.stream().noneMatch(member -> member.lease.slot() == slot)) {
                    break;
                }
                free++;
            }
            require(value.lease().slot() == free, "Credit active slot");
            if (ticket != null) {
                require(
                        ticket.producerDrained
                                && (ticket.offered ? ticket.inputConsumed : ticket.fileRetired)
                                && (ticket.commitResolved || ticket.canceled || ticket.rejected),
                        "Credit before old owners drain");
                ticket.credited = true;
            }
            credit = value.lease();
            serial = credit.serial();
            generations[credit.slot()] = credit.generation();
            return Change.CREDIT;
        }
        if (message instanceof WorldStreamStatus.CameraApplied) {
            return Change.CAMERA;
        }
        if (message instanceof WorldStreamStatus.FramePublished) {
            return Change.FRAME;
        }
        if (message instanceof WorldStreamStatus.InputConsumed value) {
            if (value.kind() == WorldStreamStatus.InputKind.TILE) {
                require(
                        ticket != null
                                && ticket.offered
                                && ticket.transaction == value.transaction()
                                && !ticket.inputConsumed,
                        "Unknown/duplicate tile input");
                ticket.inputConsumed = true;
                return Change.INPUT_TILE;
            }
            require(
                    commit != null
                            && commit.transaction == value.transaction()
                            && !commit.inputConsumed,
                    "Unknown/duplicate commit input");
            commit.inputConsumed = true;
            return Change.INPUT_COMMIT;
        }
        if (message instanceof WorldStreamStatus.Staged value) {
            require(
                    ticket != null
                            && ticket.transaction == value.transaction()
                            && ticket.lease.equals(value.lease())
                            && ticket.offered
                            && ticket.inputConsumed
                            && ticket.resident == null
                            && !ticket.rejected
                            && !ticket.canceled
                            && ticket.outcome == value.outcome()
                            && ticket.coverage == value.coverage()
                            && (session.exteriorObjects()
                                    ? ticket.objectsOffered
                                            ? value.objects() == 1 || value.objects() == 2
                                            : value.objects() == 0
                                    : value.objects() == -1),
                    "Unmatched staged payload");
            ticket.resident = new Resident(ticket, value.objects());
            return Change.STAGED;
        }
        if (message instanceof WorldStreamStatus.Committed value) {
            require(
                    commit != null
                            && !commit.terminal
                            && commit.inputConsumed
                            && commit.transaction == value.transaction()
                            && commit.expected == value.expected()
                            && commit.next == value.next(),
                    "Unmatched committed table");
            active = commit.ordered;
            content = commit.next;
            commit.terminal = commit.accepted = true;
            if (ticket != null && ticket.resident != null) {
                ticket.commitResolved = true;
                ticket.committed = active.contains(ticket.resident);
            }
            return Change.COMMITTED;
        }
        if (message instanceof WorldStreamStatus.Rejected value) {
            require(value.transaction() != 0, "Session rejected");
            if (ticket != null
                    && ticket.transaction == value.transaction()
                    && ticket.cancelSent
                    && !ticket.cancelResolved
                    && ticket.rejected
                    && value.reason() == WorldStreamStatus.Rejection.STALE_TRANSACTION) {
                ticket.cancelResolved = true;
                return Change.CANCEL_REJECTED;
            }
            if (ticket != null
                    && ticket.transaction == value.transaction()
                    && ticket.offered
                    && ticket.inputConsumed
                    && !ticket.rejected
                    && ticket.resident == null
                    && value.reason() == WorldStreamStatus.Rejection.INVALID_PACKET) {
                ticket.rejected = true;
                return Change.REJECTED;
            }
            require(
                    commit != null
                            && !commit.terminal
                            && commit.transaction == value.transaction()
                            && commit.inputConsumed
                            && (value.reason() == WorldStreamStatus.Rejection.INVALID_COMMIT
                                    || value.reason() == WorldStreamStatus.Rejection.INVALID_TABLE
                                    || value.reason()
                                            == WorldStreamStatus.Rejection.REVISION_MISMATCH),
                    "Unmatched/fatal rejection");
            commit.terminal = true;
            return Change.REJECTED;
        }
        if (message instanceof WorldStreamStatus.Canceled value) {
            require(
                    ticket != null
                            && ticket.transaction == value.transaction()
                            && ticket.lease.equals(value.lease())
                            && !ticket.canceled
                            && (ticket.cancelSent && ticket.inputConsumed
                                    || ticket.unusedSent
                                            && ticket.producerDrained
                                            && ticket.fileRetired),
                    "Unmatched cancellation");
            ticket.canceled = true;
            ticket.cancelResolved = true;
            return Change.CANCELED;
        }
        if (message instanceof WorldStreamStatus.HorizonDone) {
            // Only the one answer to a sent HORIZON counts; anything else is ignored, never fatal.
            if (!horizonSent || horizonAnswered) {
                return Change.IGNORED;
            }
            horizonAnswered = true;
            return Change.HORIZON;
        }
        if (message instanceof WorldStreamStatus.ObjectsDone value) {
            require(
                    objectsTarget != null
                            && objectsTarget.transaction == value.transaction()
                            && objectsTarget.lease.equals(value.lease()),
                    "Unmatched late objects outcome");
            objectsTarget.late = value.outcome();
            objectsTarget = null;
            return Change.OBJECTS;
        }
        require(message instanceof WorldStreamStatus.Closed, "Unknown status");
        closed = true;
        return Change.CLOSED;
    }

    enum Cancellation {
        PENDING,
        CANCELED,
        REJECTED,
        COMMITTED,
        COMMIT_PENDING,
        RETIRED
    }

    synchronized Cancellation cancellation(Ticket value) {
        require(value != null && value.owner == owner, "Foreign tile cancellation");
        if (value.canceled && value.producerDrained && value.fileRetired) {
            return Cancellation.CANCELED;
        }
        if (value.rejected && (!value.cancelSent || value.cancelResolved)) {
            return Cancellation.REJECTED;
        }
        if (value.commitResolved) {
            return active.contains(value.resident) ? Cancellation.COMMITTED : Cancellation.RETIRED;
        }
        if (value == ticket && value.resident != null && commit != null && !commit.terminal) {
            return Cancellation.COMMIT_PENDING;
        }
        if (value.credited && !value.canceled && !value.rejected) {
            return Cancellation.RETIRED;
        }
        return Cancellation.PENDING;
    }

    synchronized List<Resident> active() {
        return active;
    }

    synchronized long content() {
        return content;
    }

    synchronized void close() {
        closed = true;
        active = List.of();
    }

    /** Called only from actual owned Process.onExit; EOF/CLOSED alone cannot authorize cleanup. */
    synchronized void processExited() {
        childGone = true;
        close();
    }

    private long nextTransaction() {
        require(transaction < Long.MAX_VALUE - 1, "Parent transaction exhausted");
        return ++transaction;
    }

    private void requireIdentity(Ticket value) {
        require(
                value != null && value.owner == owner && value == ticket,
                "Expired tile reservation");
    }

    private void requireTicket(Ticket value) {
        require(
                !closed && value != null && value.owner == owner && value == ticket,
                "Expired tile reservation");
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalStateException(message);
        }
    }
}
