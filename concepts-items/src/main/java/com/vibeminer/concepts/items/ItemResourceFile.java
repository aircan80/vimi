package com.vibeminer.concepts.items;

import com.vibeminer.formats.tables.TableTypes;

public record ItemResourceFile(long fileDataId, TableTypes.Row row, ItemComponentKey component) {}
