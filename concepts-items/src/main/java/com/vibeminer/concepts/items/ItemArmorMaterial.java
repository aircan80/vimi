package com.vibeminer.concepts.items;

import com.vibeminer.formats.tables.TableTypes;

/**
 * Exact source row and numeric section; no body-section label or texture application is inferred.
 */
public record ItemArmorMaterial(
        TableTypes.Row row,
        Long componentSection,
        Long materialResourcesId,
        ItemResourceRef material) {}
