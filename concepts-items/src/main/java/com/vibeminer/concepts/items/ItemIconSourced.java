package com.vibeminer.concepts.items;

public record ItemIconSourced(String table, String column, long rowId, ItemIcon icon) {}
