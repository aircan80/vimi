package com.vibeminer.concepts.items;

import com.vibeminer.formats.tables.TableTypes;
import java.util.List;

public record ItemClassLabel(
        ItemClassLabelState state,
        List<ItemSourceValue> sourceKeys,
        String text,
        TableTypes.Row target,
        Integer textColumn,
        Long candidates,
        long scanned,
        TableTypes.Entry table,
        String code,
        String message) {
    public ItemClassLabel {
        sourceKeys = List.copyOf(sourceKeys);
    }
}
