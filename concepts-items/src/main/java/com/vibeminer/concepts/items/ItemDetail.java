package com.vibeminer.concepts.items;

import com.vibeminer.formats.tables.TableTypes;
import java.util.List;

public record ItemDetail(
        ItemSummary summary,
        TableTypes.Row itemRow,
        TableTypes.Row sparseRow,
        ItemIcon icon,
        ItemFactSet facts,
        ItemClassLabels labels,
        List<ItemIconSourced> icons,
        ItemAppearanceSet appearances) {
    /** Display-ready direct base attributes; never interprets allocation inputs as flat values. */
    public ItemAttributes attributes() {
        return ItemAttributes.from(facts);
    }

    public ItemDetail {
        icons = List.copyOf(icons);
    }
}
