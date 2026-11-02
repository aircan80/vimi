package com.vibeminer.concepts.items;

import com.vibeminer.formats.tables.TableTypes;

public record ItemResult(
        ItemResultState state,
        ItemDetail detail,
        TableTypes.Entry item,
        TableTypes.Entry sparse,
        String protectedKey,
        String code,
        String message) {}
