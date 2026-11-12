package com.vibeminer.rendering.bgfx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.rendering.world.TerrainCamera;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class WorldControlMailboxTest {
    private static final WorldStreamState.Nonce NONCE = new WorldStreamState.Nonce(1, 2);

    private static WorldStreamControl.Camera camera(long revision, long policy, int width) {
        return new WorldStreamControl.Camera(
                NONCE, revision, policy, width, 500, new TerrainCamera(0, 0, 0, 0, 0));
    }

    @Test
    void establishedCancelAloneBypassesDeferredNewTransactionsWithoutReorderingThem() {
        var mailbox = new WorldControlMailbox(NONCE);
        var lease = new WorldStreamState.Lease(0, 1, 1);
        var offer = new WorldStreamControl.Offer(NONCE, 1, lease, 4096, "00".repeat(32));
        var commit = new WorldStreamControl.Commit(NONCE, 2, "00".repeat(32));
        var unused = new WorldStreamControl.CancelUnused(NONCE, 3, lease);
        var cancel = new WorldStreamControl.Cancel(NONCE, 1, 0, 1);
        mailbox.offer(offer);
        mailbox.offer(commit);
        mailbox.offer(unused);
        mailbox.offer(cancel);
        assertEquals(cancel, mailbox.takeCancellation());
        assertNull(mailbox.takeCancellation());
        assertEquals(offer, mailbox.takeTransaction());
        assertEquals(commit, mailbox.takeTransaction());
        assertEquals(unused, mailbox.takeTransaction());
        assertNull(mailbox.takeTransaction());
        var second = new WorldStreamControl.Cancel(NONCE, 4, 1, 2);
        mailbox.offer(cancel);
        mailbox.offer(commit);
        mailbox.offer(second);
        assertEquals(cancel, mailbox.takeCancellation());
        assertEquals(second, mailbox.takeCancellation());
        assertEquals(commit, mailbox.takeTransaction());
        mailbox.offer(cancel);
        mailbox.offer(new WorldStreamControl.Close(NONCE));
        assertNull(mailbox.takeCancellation());
    }

    @Test
    void cameraAndCloseAreIndependentOfFullTransactionQueue() {
        var mailbox = new WorldControlMailbox(NONCE);
        for (int i = 1; i <= 4; i++) {
            assertEquals(
                    WorldControlMailbox.Result.ACCEPTED,
                    mailbox.offer(new WorldStreamControl.Cancel(NONCE, i, 0, 1)));
        }
        assertEquals(WorldControlMailbox.Result.ACCEPTED, mailbox.offer(camera(1, 1, 800)));
        assertEquals(WorldControlMailbox.Result.ACCEPTED, mailbox.offer(camera(2, 1, 800)));
        assertEquals(2, mailbox.takeCamera().revision());
        assertNull(mailbox.takeCamera());
        assertEquals(
                WorldControlMailbox.Result.ACCEPTED,
                mailbox.offer(new WorldStreamControl.Close(NONCE)));
        assertTrue(mailbox.closing());
        assertFalse(mailbox.failed());
        assertNull(mailbox.takeTransaction());
        assertEquals(WorldControlMailbox.Result.CLOSED, mailbox.offer(camera(3, 1, 800)));
        assertEquals(
                WorldControlMailbox.Result.ACCEPTED,
                mailbox.offer(new WorldStreamControl.Close(NONCE)));
    }

    @Test
    void overflowTerminatesRatherThanRetainingUnboundedCommands() {
        var mailbox = new WorldControlMailbox(NONCE);
        for (int i = 1; i <= 4; i++) {
            mailbox.offer(new WorldStreamControl.Cancel(NONCE, i, 0, 1));
        }
        assertEquals(
                WorldControlMailbox.Result.OVERFLOW,
                mailbox.offer(new WorldStreamControl.Cancel(NONCE, 5, 0, 1)));
        assertTrue(mailbox.closing());
        assertTrue(mailbox.failed());
        assertNull(mailbox.takeTransaction());
        assertEquals(
                WorldControlMailbox.Result.CLOSED,
                mailbox.offer(new WorldStreamControl.Cancel(NONCE, 6, 0, 1)));
    }

    @Test
    void resizePolicyAndRevisionSurviveCoalescingAndConsumption() {
        var mailbox = new WorldControlMailbox(NONCE);
        mailbox.offer(camera(1, 1, 800));
        mailbox.offer(camera(2, 2, 700));
        assertEquals(WorldControlMailbox.Result.STALE_CAMERA, mailbox.offer(camera(3, 1, 800)));
        assertEquals(WorldControlMailbox.Result.STALE_CAMERA, mailbox.offer(camera(3, 2, 800)));
        assertEquals(2, mailbox.takeCamera().revision());
        assertEquals(WorldControlMailbox.Result.STALE_CAMERA, mailbox.offer(camera(2, 2, 700)));
        assertEquals(WorldControlMailbox.Result.ACCEPTED, mailbox.offer(camera(3, 2, 700)));
        assertEquals(3, mailbox.takeCamera().revision());
        assertEquals(
                WorldControlMailbox.Result.WRONG_SESSION,
                mailbox.offer(new WorldStreamControl.Close(new WorldStreamState.Nonce(3, 4))));
        assertFalse(mailbox.closing());
    }

    @Test
    void transactionsRetainFifoWhileCameraPublisherCoalescesConcurrently() throws Exception {
        var mailbox = new WorldControlMailbox(NONCE);
        for (int i = 1; i <= 4; i++) {
            mailbox.offer(new WorldStreamControl.Cancel(NONCE, i, 0, 1));
        }
        var finished = new CountDownLatch(1);
        Thread publisher =
                Thread.startVirtualThread(
                        () -> {
                            try {
                                for (int i = 1; i <= 10_000; i++) {
                                    assertEquals(
                                            WorldControlMailbox.Result.ACCEPTED,
                                            mailbox.offer(camera(i, 1, 800)));
                                }
                            } finally {
                                finished.countDown();
                            }
                        });
        for (int i = 1; i <= 4; i++) {
            assertEquals(i, ((WorldStreamControl.Cancel) mailbox.takeTransaction()).transaction());
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        publisher.join();
        assertEquals(10_000, mailbox.takeCamera().revision());
        assertNull(mailbox.takeTransaction());
    }

    @Test
    void theHorizonLaneTakesOneCommandAndIgnoresRepeatsWithoutFailing() {
        var mailbox = new WorldControlMailbox(NONCE);
        var first = new WorldStreamControl.Horizon(NONCE, 100, "d".repeat(64));
        assertEquals(WorldControlMailbox.Result.ACCEPTED, mailbox.offer(first));
        assertEquals(
                WorldControlMailbox.Result.ACCEPTED,
                mailbox.offer(new WorldStreamControl.Horizon(NONCE, 200, "e".repeat(64))));
        assertEquals(first, mailbox.takeHorizon());
        assertNull(mailbox.takeHorizon());
        assertEquals(
                WorldControlMailbox.Result.ACCEPTED,
                mailbox.offer(new WorldStreamControl.Horizon(NONCE, 300, "f".repeat(64))));
        assertNull(mailbox.takeHorizon());
        assertFalse(mailbox.failed());
        assertFalse(mailbox.closing());
    }

    @Test
    void theBodyLaneKeepsTheNewestPendingCommand() {
        var mailbox = new WorldControlMailbox(NONCE);
        var first = new WorldStreamControl.BodyModel(NONCE, 100, "d".repeat(64));
        assertEquals(WorldControlMailbox.Result.ACCEPTED, mailbox.offer(first));
        assertEquals(first, mailbox.takeBody());
        assertNull(mailbox.takeBody());
        // A later body (a swap) is taken in turn; a newer pending one replaces an older one.
        var second = new WorldStreamControl.BodyModel(NONCE, 200, "e".repeat(64));
        var third = new WorldStreamControl.BodyModel(NONCE, 300, "f".repeat(64));
        assertEquals(WorldControlMailbox.Result.ACCEPTED, mailbox.offer(second));
        assertEquals(WorldControlMailbox.Result.ACCEPTED, mailbox.offer(third));
        assertEquals(third, mailbox.takeBody());
        assertNull(mailbox.takeBody());
        assertNull(mailbox.takeHorizon(), "its own lane");
        assertFalse(mailbox.failed());
    }
}
