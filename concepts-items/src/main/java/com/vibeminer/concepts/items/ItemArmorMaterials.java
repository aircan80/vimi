package com.vibeminer.concepts.items;

import com.vibeminer.formats.tables.TableTypes;
import java.util.List;

/**
 * Armor material-resource rows, including partial hidden-key context and exact matching-row count.
 */
public record ItemArmorMaterials(
        ItemArmorMaterialsState state,
        List<ItemArmorMaterial> rows,
        int matches,
        TableTypes.Entry table,
        List<String> keyNames,
        String code,
        String message) {
    public ItemArmorMaterials {
        rows = List.copyOf(rows);
        keyNames = keyNames == null ? List.of() : List.copyOf(keyNames);
    }
}
