package com.vibeminer.questie.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.activity.NetworkActivity;
import com.vibeminer.activity.TransferObserver;
import com.vibeminer.generated.Cancellation;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

final class GuardedBodyActivityTest {
    @Test
    void positiveReadIsCountedBeforePostReadCancellationCheck() throws Exception {
        var meter = new NetworkActivity();
        var token = new Cancellation();
        var source =
                new InputStream() {
                    @Override
                    public int read() {
                        return -1;
                    }

                    @Override
                    public int read(byte[] bytes, int offset, int length) {
                        bytes[offset] = 1;
                        token.cancel();
                        return 1;
                    }
                };
        try (var body =
                new QuestieSourceRepository.GuardedBody(
                        source, token, 10000, meter.begin(TransferObserver.Kind.QUESTIE))) {
            assertThrows(CancellationException.class, () -> body.read(new byte[4]));
            assertEquals(1, meter.snapshot().receivedBytes());
            assertEquals(0, meter.snapshot().active());
        }
    }

    @Test
    void eofEndsActivityBeforeWrapperCloseAndSingleByteReadsCountOnce() throws Exception {
        var meter = new NetworkActivity();
        try (var body =
                new QuestieSourceRepository.GuardedBody(
                        new ByteArrayInputStream(new byte[] {1, 2}),
                        Cancellation.NONE,
                        10000,
                        meter.begin(TransferObserver.Kind.QUESTIE))) {
            assertEquals(1, body.read());
            assertEquals(2, body.read());
            assertEquals(2, meter.snapshot().receivedBytes());
            assertEquals(-1, body.read());
            assertEquals(0, meter.snapshot().active());
        }
    }

    @Test
    void failedCloseStillEndsAttemptExactlyOnce() {
        var meter = new NetworkActivity();
        var source =
                new ByteArrayInputStream(new byte[] {1}) {
                    @Override
                    public void close() throws IOException {
                        throw new IOException("authored close failure");
                    }
                };
        var body =
                new QuestieSourceRepository.GuardedBody(
                        source,
                        Cancellation.NONE,
                        10000,
                        meter.begin(TransferObserver.Kind.QUESTIE));
        assertThrows(IOException.class, body::close);
        assertEquals(0, meter.snapshot().active());
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(body::close);
    }
}
