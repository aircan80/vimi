package com.vibeminer.concepts.items;

import java.util.List;

public record ItemSummary(
        long id,
        boolean isCopy,
        Long copySourceId,
        ItemName name,
        List<ItemSourceValue> values,
        Long iconFileDataId,
        String sparseKey,
        ItemRowState item,
        String itemKey) {
    public ItemSummary {
        values = List.copyOf(values);
    }
}
