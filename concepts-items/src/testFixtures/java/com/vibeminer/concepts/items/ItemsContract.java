package com.vibeminer.concepts.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.vibeminer.access.Cancellation;
import com.vibeminer.builds.BuildTypes.BuildRef;
import com.vibeminer.formats.tables.TableTypes;
import org.junit.jupiter.api.Test;

/**
 * Reusable public consumer contract; implementations seed ids0,2,uintMax named Sword,space,empty.
 */
public interface ItemsContract {
    Items createItems();

    BuildRef build();

    default BuildItems open() {
        return createItems().forBuild(build(), "enUS", new Cancellation()).join().items();
    }

    @Test
    default void orderedPagesAndExactId() {
        try (var h = open()) {
            var token = new Cancellation();
            var p =
                    h.list(new ItemQuery("", null, 1, null), TableTypes.Priority.INTERACTIVE, token)
                            .join();
            assertEquals(0, p.entries().getFirst().id());
            assertNotNull(p.next());
            var p2 =
                    h.list(
                                    new ItemQuery("", null, 1, p.next()),
                                    TableTypes.Priority.INTERACTIVE,
                                    token)
                            .join();
            assertEquals(2, p2.entries().getFirst().id());
            var exact =
                    h.list(
                                    new ItemQuery("", 0xffffffffL, 50, null),
                                    TableTypes.Priority.BACKGROUND,
                                    token)
                            .join();
            assertEquals(0xffffffffL, exact.entries().getFirst().id());
            assertNull(exact.next());
        }
    }

    @Test
    default void nameSearchUsesLiteralNonemptyNames() {
        try (var h = open()) {
            var token = new Cancellation();
            assertEquals(
                    1,
                    h.list(new ItemQuery("sWoRd"), TableTypes.Priority.INTERACTIVE, token)
                            .join()
                            .entries()
                            .size());
            assertEquals(
                    1,
                    h.list(new ItemQuery(" "), TableTypes.Priority.INTERACTIVE, token)
                            .join()
                            .entries()
                            .size());
            assertEquals(
                    0,
                    h.list(new ItemQuery("%"), TableTypes.Priority.INTERACTIVE, token)
                            .join()
                            .entries()
                            .size());
        }
    }

    @Test
    default void detailsHaveStableFactProjectionAndTypedMissing() {
        try (var h = open()) {
            var token = new Cancellation();
            var detail = h.get(0, TableTypes.Priority.INTERACTIVE, token).join();
            assertEquals(ItemResultState.FOUND, detail.state());
            assertEquals(48, detail.detail().facts().facts().size());
            assertEquals(
                    ItemResultState.ABSENT,
                    h.get(123, TableTypes.Priority.INTERACTIVE, token).join().state());
        }
    }

    @Test
    default void cursorBindingRejectsChangedLimit() {
        try (var h = open()) {
            var token = new Cancellation();
            var p =
                    h.list(new ItemQuery("", null, 1, null), TableTypes.Priority.INTERACTIVE, token)
                            .join();
            assertEquals(
                    ItemsPageState.INVALID_CURSOR,
                    h.list(
                                    new ItemQuery("", null, 2, p.next()),
                                    TableTypes.Priority.INTERACTIVE,
                                    token)
                            .join()
                            .state());
        }
    }

    @Test
    default void invalidArgumentsCancellationAndClosedHandle() {
        var source = createItems();
        assertThrows(
                IllegalArgumentException.class,
                () -> source.forBuild(build(), "nope", new Cancellation()));
        var h = open();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        h.list(
                                new ItemQuery("", null, 0, null),
                                TableTypes.Priority.INTERACTIVE,
                                new Cancellation()));
        var cancel = new Cancellation();
        cancel.cancel();
        assertThrows(
                java.util.concurrent.CancellationException.class,
                () -> h.get(0, TableTypes.Priority.INTERACTIVE, cancel));
        h.close();
        assertThrows(
                IllegalStateException.class,
                () -> h.get(0, TableTypes.Priority.INTERACTIVE, new Cancellation()));
    }
}
