package com.vibeminer.formats.tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.access.Cancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
final class TableWorkSchedulerTest {
    @Test
    void interactiveQueueTakesPrecedenceAndCancellationUnblocksBackground() {
        var scheduler = new TableWorkScheduler(1, 1, 1);
        var interactive =
                scheduler.enter(TableTypes.Priority.INTERACTIVE, Cancellation.NONE).join();
        var background = scheduler.enter(TableTypes.Priority.BACKGROUND, Cancellation.NONE).join();
        var canceled = new Cancellation();
        var waitingInteractive = scheduler.enter(TableTypes.Priority.INTERACTIVE, canceled);
        var waitingBackground = scheduler.enter(TableTypes.Priority.BACKGROUND, Cancellation.NONE);
        assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> scheduler.enter(TableTypes.Priority.BACKGROUND, Cancellation.NONE).join());
        background.close();
        assertFalse(waitingBackground.isDone());
        assertEquals(1, scheduler.queued(TableTypes.Priority.INTERACTIVE));
        canceled.cancel();
        assertThrows(java.util.concurrent.CancellationException.class, waitingInteractive::join);
        var next = waitingBackground.join();
        assertEquals(0, scheduler.queued(TableTypes.Priority.BACKGROUND));
        next.close();
        interactive.close();
        assertEquals(0, scheduler.running(TableTypes.Priority.INTERACTIVE));
        assertEquals(0, scheduler.running(TableTypes.Priority.BACKGROUND));
    }
}
