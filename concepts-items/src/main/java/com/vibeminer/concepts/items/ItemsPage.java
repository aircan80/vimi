package com.vibeminer.concepts.items;

import com.vibeminer.formats.tables.TableTypes;
import java.util.List;

public record ItemsPage(
        ItemsPageState state,
        List<ItemSummary> entries,
        String next,
        long scanned,
        TableTypes.Entry item,
        TableTypes.Entry sparse,
        String code,
        String message) {
    public ItemsPage {
        entries = List.copyOf(entries);
    }
}
