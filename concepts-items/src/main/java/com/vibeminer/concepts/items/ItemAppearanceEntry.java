package com.vibeminer.concepts.items;

import com.vibeminer.formats.tables.TableTypes;

public record ItemAppearanceEntry(
        TableTypes.Row row,
        Long itemAppearanceModifierId,
        Long orderIndex,
        Long itemAppearanceId,
        ItemAppearanceRef appearance) {}
