package com.vibeminer.concepts.items;

public record ItemFactValue(
        String table, String column, int columnIndex, long rowId, String value, String label) {}
