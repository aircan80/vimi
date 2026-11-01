package com.vibeminer.builds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vibeminer.access.Cancellation;
import java.time.Instant;
import org.junit.jupiter.api.Test;

final class CdnsRoutesTest {
    private static String table(int seq, String rows) {
        return "Name!STRING:0|Path!STRING:0|Hosts!STRING:0\n## seqn = " + seq + "\n" + rows;
    }

    @Test
    void numericSequenceWinsAndNullRegionChoosesUsThenAnyPerReply() {
        CatalogSource source = new CatalogSource();
        source.append(
                2,
                "wow",
                "us",
                Instant.EPOCH,
                table(2, "us|bad/path|evil.test\neu|tpr/new|eu.cdn.blizzard.com\n"));
        source.append(
                2,
                "wow",
                "us",
                Instant.EPOCH.plusSeconds(100),
                table(1, "us|tpr/old|us.cdn.blizzard.com\n"));
        var replies = CdnsRoutes.newestFirst(source, "wow", Cancellation.NONE).join();
        assertEquals("tpr/old", CdnsRoutes.route(replies, "us").path());
        assertEquals("tpr/new", CdnsRoutes.route(replies, null).path());
        assertNull(CdnsRoutes.route(replies, "kr"));
        assertTrue(source.asked().isEmpty());
    }

    @Test
    void duplicateReplyDocumentsAreCoalescedAndUnreadableRepliesAreSkipped() {
        CatalogSource source = new CatalogSource();
        String good = table(1, "us|tpr/wow|us.cdn.blizzard.com\n");
        source.append(2, "wow", "us", Instant.EPOCH, good);
        source.append(2, "wow", "eu", Instant.EPOCH.plusSeconds(1), good);
        source.append(2, "wow", "us", Instant.EPOCH.plusSeconds(2), "invalid");
        var replies = CdnsRoutes.newestFirst(source, "wow", Cancellation.NONE).join();
        assertEquals(1, replies.size());
        assertEquals("tpr/wow", CdnsRoutes.route(replies, "us").path());
    }

    @Test
    void routeHistoryIsBoundedToTheMostRecentlyObservedDistinctReplies() {
        CatalogSource source = new CatalogSource();
        for (int i = 0; i < 100; i++) {
            source.append(
                    2,
                    "wow",
                    "us",
                    Instant.EPOCH.plusSeconds(i),
                    table(i, "us|tpr/wow|us.cdn.blizzard.com\n"));
        }
        var replies = CdnsRoutes.newestFirst(source, "wow", Cancellation.NONE).join();
        assertEquals(32, replies.size());
        assertEquals("99", replies.getFirst().sequence());
    }
}
