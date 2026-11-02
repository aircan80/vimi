package com.vibeminer.concepts.items;

import com.vibeminer.formats.tables.TableTypes;
import java.util.List;

public record ItemDisplayRef(
        ItemRefState state,
        Long id,
        TableTypes.Row row,
        List<ItemDisplaySlot> slots,
        TableTypes.Entry table,
        String protectedKey,
        String code,
        String message,
        ItemArmorMaterials armorMaterials) {
    public ItemDisplayRef(
            ItemRefState state,
            Long id,
            TableTypes.Row row,
            List<ItemDisplaySlot> slots,
            TableTypes.Entry table,
            String protectedKey,
            String code,
            String message) {
        this(state, id, row, slots, table, protectedKey, code, message, null);
    }

    public ItemDisplayRef {
        slots = List.copyOf(slots);
    }
}
