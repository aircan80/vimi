package com.vibeminer.concepts.items;

import com.vibeminer.formats.tables.TableTypes;

public record ItemComponentKey(
        ItemRefState state,
        Long raceId,
        Long genderIndex,
        Long classId,
        Long positionIndex,
        TableTypes.Row row,
        TableTypes.Entry table,
        String protectedKey,
        String code,
        String message) {}
