package com.vibeminer.concepts.items;

/** Whole-catalog query. Filtering and stable sorting precede LIMIT/OFFSET. */
public record ItemGridQuery(
        String search,
        String itemClass,
        String subclass,
        String quality,
        String slot,
        Sort sort,
        boolean descending,
        int offset,
        int limit) {
    public enum Sort {
        ID,
        NAME,
        CLASS,
        SUBCLASS,
        QUALITY,
        SLOT,
        LEVEL
    }

    public ItemGridQuery {
        if (search == null
                || !ItemQueries.isValidQuery(search)
                || sort == null
                || offset < 0
                || limit < 1
                || limit > 500) {
            throw new IllegalArgumentException("Invalid item grid query");
        }
    }

    public ItemGridQuery() {
        this("", null, null, null, null, Sort.ID, false, 0, 100);
    }
}
