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
}
