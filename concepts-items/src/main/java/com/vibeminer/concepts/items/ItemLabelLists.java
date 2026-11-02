package com.vibeminer.concepts.items;

import java.util.List;

public record ItemLabelLists(
        String labelRevision,
        List<ItemLabel> quality,
        List<ItemLabel> inventoryType,
        List<ItemLabel> bonding,
        List<ItemLabel> trigger) {
    public ItemLabelLists {
        quality = List.copyOf(quality);
        inventoryType = List.copyOf(inventoryType);
        bonding = List.copyOf(bonding);
        trigger = List.copyOf(trigger);
    }
}
