package com.vibeminer.concepts.items;

/** Lightweight grid projection; item detail and media are separate requests. */
public record ItemGridRow(
        long id,
        String name,
        Long iconFileDataId,
        String classId,
        String subclassId,
        String itemClass,
        String subclass,
        String qualityId,
        String quality,
        String slotId,
        String slot,
        Long itemLevel) {}
