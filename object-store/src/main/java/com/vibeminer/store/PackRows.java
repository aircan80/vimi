package com.vibeminer.store;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Keyset pages pack rows without retaining metadata for the complete store. Columns start with seq.
 */
final class PackRows {
    private PackRows() {}

    static final class Snapshot implements AutoCloseable {
        private final NativeSqlite db;
        private final boolean owned;

        Snapshot(NativeSqlite db) {
            this.db = db;
            owned = !db.inTransaction();
            if (owned) {
                db.exec("BEGIN");
            }
        }

        Iterable<Object[]> rows(String columns) {
            return all(db, columns);
        }

        @Override
        public void close() {
            if (owned) {
                db.uncancelled(() -> db.exec("ROLLBACK"));
            }
        }
    }

    static Iterable<Object[]> all(NativeSqlite db, String columns) {
        return () ->
                new Iterator<>() {
                    private List<Object[]> page = List.of();
                    private int position;
                    private Long cursor;
                    private boolean finished;

                    @Override
                    public boolean hasNext() {
                        if (position < page.size()) {
                            return true;
                        }
                        if (finished) {
                            return false;
                        }
                        page =
                                cursor == null
                                        ? db.query(
                                                "SELECT "
                                                        + columns
                                                        + " FROM packs ORDER BY seq LIMIT "
                                                        + MaintenanceSpool.PAGE_ROWS)
                                        : db.query(
                                                "SELECT "
                                                        + columns
                                                        + " FROM packs WHERE seq>? ORDER BY seq LIMIT "
                                                        + MaintenanceSpool.PAGE_ROWS,
                                                cursor);
                        position = 0;
                        finished = page.isEmpty();
                        return !finished;
                    }

                    @Override
                    public Object[] next() {
                        if (!hasNext()) {
                            throw new NoSuchElementException();
                        }
                        Object[] row = page.get(position++);
                        cursor = (long) row[0];
                        return row;
                    }
                };
    }
}
