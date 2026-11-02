package com.vibeminer.concepts.items;

import com.vibeminer.formats.tables.TableTypes;

public record ItemAppearanceRef(
        ItemRefState state,
        Long id,
        TableTypes.Row row,
        Long defaultIconFileDataId,
        Long itemDisplayInfoId,
        ItemDisplayRef display,
        TableTypes.Entry table,
        String protectedKey,
        String code,
        String message) {}
