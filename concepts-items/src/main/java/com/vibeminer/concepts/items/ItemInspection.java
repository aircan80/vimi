package com.vibeminer.concepts.items;

/** Data-only selected-item details and backend-resolved base attributes. */
public record ItemInspection(ItemResult item, ItemAttributes attributes, String scope) {}
